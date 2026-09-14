package com.nest.jsonstore.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.nest.jsonstore.config.LimitsProperties;
import com.nest.jsonstore.error.InvalidInputsException;
import com.nest.jsonstore.error.PayloadTooLargeException;
import com.nest.jsonstore.error.ProfileNotFoundException;
import com.nest.jsonstore.error.VersionMismatchException;
import com.nest.jsonstore.profile.dto.PageResponse;
import com.nest.jsonstore.profile.dto.ProfileRequest;
import com.nest.jsonstore.profile.dto.ProfileResponse;
import com.nest.jsonstore.profile.dto.ProfileStats;
import com.nest.jsonstore.profile.dto.ProfileSummary;
import com.nest.jsonstore.profile.dto.RecomposeOutcome;
import com.nest.jsonstore.profile.dto.TemplateRequest;
import com.nest.jsonstore.template.TemplateInputs;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class ProfileService {

    /** API sort keys mapped onto real columns, so the sort parameter can never reach SQL unchecked. */
    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "name", "name",
            "createdAt", "created_at",
            "updatedAt", "updated_at",
            "sizeBytes", "size_bytes");

    /** The most tags a profile carries; the rest are dropped rather than refused. */
    static final int MAX_TAGS = 12;

    private final ProfileRepository repository;
    private final ProfileMapper mapper;
    private final LimitsProperties limits;
    private final TemplateInputs inputs;

    ProfileService(ProfileRepository repository, ProfileMapper mapper, LimitsProperties limits, TemplateInputs inputs) {
        this.repository = repository;
        this.mapper = mapper;
        this.limits = limits;
        this.inputs = inputs;
    }

    public PageResponse<ProfileSummary> list(String search, String tag, int page, int size, String sort, String direction) {
        return PageResponse.of(
                repository.search(escapeLike(trimToNull(search)), trimToNull(tag), pageable(page, size, sort, direction)),
                mapper::toSummary);
    }

    public ProfileResponse get(UUID id) {
        return repository.findById(id)
                .map(mapper::toResponse)
                .orElseThrow(() -> new ProfileNotFoundException(id));
    }

    @Transactional
    public ProfileResponse create(ProfileRequest request) {
        if (request.template() == null) {
            throw new InvalidInputsException("template", "A profile is built from templates: choose at least the required ones");
        }
        TemplateInputs.Built built = build(request.template());
        Profile profile = new Profile(
                request.name().trim(),
                trimToNull(request.description()),
                normalizeTags(request.tags()),
                built.documents(),
                checkedSize(built),
                built.template(),
                actor());
        // Flush so the generated id, timestamps and version are in the entity before it is mapped.
        return mapper.toResponse(repository.saveAndFlush(profile));
    }

    /**
     * @param expected the version the change was made to, from If-Match; empty to overwrite whatever
     *                 is stored
     */
    @Transactional
    public ProfileResponse update(UUID id, OptionalLong expected, ProfileRequest request) {
        Profile profile = current(id, expected);
        if (request.template() == null) {
            // Renaming or retagging a profile is not a reason to rebuild its inputs, and a profile stored
            // before templates were recorded has nothing to rebuild them from.
            profile.applyDetails(request.name().trim(), trimToNull(request.description()),
                    normalizeTags(request.tags()), actor());
        } else {
            TemplateInputs.Built built = build(request.template());
            profile.apply(
                    request.name().trim(),
                    trimToNull(request.description()),
                    normalizeTags(request.tags()),
                    built.documents(),
                    checkedSize(built),
                    built.template(),
                    actor());
        }
        // Flush so the timestamps and version are in the entity before it is mapped.
        return mapper.toResponse(repository.saveAndFlush(profile));
    }

    @Transactional
    public void delete(UUID id, OptionalLong expected) {
        repository.delete(current(id, expected));
    }

    /**
     * The stored profile, provided it is still the version the caller last saw. The version column
     * catches a save that lands between this check and the commit, so the check cannot be raced.
     */
    private Profile current(UUID id, OptionalLong expected) {
        Profile profile = repository.findById(id).orElseThrow(() -> new ProfileNotFoundException(id));
        if (expected.isPresent() && expected.getAsLong() != profile.getVersion()) {
            throw new VersionMismatchException(profile.getName(), profile.getUpdatedBy());
        }
        return profile;
    }

    public ProfileStats stats() {
        // max() over no rows is null, which is exactly what "never changed" should read as.
        return new ProfileStats(repository.count(), repository.totalBytes(), repository.lastUpdatedAt());
    }

    /**
     * Rebuilds every templated profile from its stored selection and values, against the catalogue as
     * it is now. Profiles stored before the server composed inputs itself may differ from what their
     * own template builds today — one seeded profile stored the literal text "${sku}" — and so may
     * profiles after a catalogue is corrected.
     *
     * @param apply false for a dry run that only reports; true to write the rebuilt inputs
     */
    @Transactional
    public List<RecomposeOutcome> recomposeAll(boolean apply) {
        List<RecomposeOutcome> outcomes = new ArrayList<>();
        int page = 0;
        Page<Profile> batch;
        do {
            batch = repository.findAll(PageRequest.of(page++, 200, Sort.by("id")));
            for (Profile profile : batch) {
                outcomes.add(recompose(profile, apply));
            }
        } while (batch.hasNext());
        return outcomes;
    }

    private RecomposeOutcome recompose(Profile profile, boolean apply) {
        JsonNode template = profile.getTemplate();
        if (template == null || !template.path("selection").isObject()) {
            return new RecomposeOutcome(profile.getId(), profile.getName(), "not-templated",
                    "Not built from templates, so there is nothing to rebuild it from");
        }
        Map<String, String> selection = new LinkedHashMap<>();
        template.path("selection").fields().forEachRemaining(entry -> selection.put(entry.getKey(), entry.getValue().asText()));
        Map<String, JsonNode> values = new LinkedHashMap<>();
        template.path("values").fields().forEachRemaining(entry -> values.put(entry.getKey(), entry.getValue()));

        TemplateInputs.Built built;
        try {
            built = inputs.build(selection, values);
        } catch (InvalidInputsException stale) {
            return new RecomposeOutcome(profile.getId(), profile.getName(), "invalid", stale.getMessage());
        }
        // ObjectNode equality ignores key order, which jsonb does not keep anyway.
        if (built.documents().equals(profile.getPayload())) {
            return new RecomposeOutcome(profile.getId(), profile.getName(), "unchanged", null);
        }
        if (!apply) {
            return new RecomposeOutcome(profile.getId(), profile.getName(), "would-change", null);
        }
        profile.apply(profile.getName(), profile.getDescription(), profile.getTags(),
                built.documents(), checkedSize(built), built.template(), actor());
        return new RecomposeOutcome(profile.getId(), profile.getName(), "repaired", null);
    }

    private TemplateInputs.Built build(TemplateRequest template) {
        return inputs.build(template.selection(), template.values());
    }

    /** The stored size of the inputs, refusing inputs or a template over the configured limit. */
    private int checkedSize(TemplateInputs.Built built) {
        int size = mapper.sizeOf(built.documents());
        int largest = Math.max(size, mapper.sizeOf(built.template()));
        if (largest > limits.maxPayloadBytes()) {
            throw new PayloadTooLargeException(largest, limits.maxPayloadBytes());
        }
        return size;
    }

    private Pageable pageable(int page, int size, String sort, String direction) {
        String column = SORT_COLUMNS.getOrDefault(sort, SORT_COLUMNS.get("updatedAt"));
        Sort.Direction sortDirection = "asc".equalsIgnoreCase(direction) ? Sort.Direction.ASC : Sort.Direction.DESC;
        return PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, limits.maxPageSize()), Sort.by(sortDirection, column));
    }

    private static List<String> normalizeTags(List<String> tags) {
        if (tags == null) {
            return List.of();
        }
        return tags.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .limit(MAX_TAGS)
                .toList();
    }

    /**
     * Who is making this change, from the token rather than the request body: a client cannot
     * claim to be someone else, and there is nothing to validate. Null when there is no
     * authenticated caller at all, which outside tests the filter chain does not allow.
     */
    private static String actor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null || !authentication.isAuthenticated() ? null : authentication.getName();
    }

    private static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    /**
     * The search term goes into an ILIKE pattern, where {@code %} and {@code _} are wildcards. A user
     * typing {@code 100%} means the characters, so they are escaped; PostgreSQL's default escape
     * character is the backslash.
     */
    static String escapeLike(String term) {
        return term == null ? null : term.replaceAll("([\\\\%_])", "\\\\$1");
    }
}
