package com.nest.jsonstore.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.nest.jsonstore.config.LimitsProperties;
import com.nest.jsonstore.error.InvalidDocumentsException;
import com.nest.jsonstore.error.InvalidTemplateException;
import com.nest.jsonstore.error.PayloadTooLargeException;
import com.nest.jsonstore.error.ProfileNotFoundException;
import com.nest.jsonstore.profile.dto.PageResponse;
import com.nest.jsonstore.profile.dto.ProfileRequest;
import com.nest.jsonstore.profile.dto.ProfileResponse;
import com.nest.jsonstore.profile.dto.ProfileStats;
import com.nest.jsonstore.profile.dto.ProfileSummary;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
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

    ProfileService(ProfileRepository repository, ProfileMapper mapper, LimitsProperties limits) {
        this.repository = repository;
        this.mapper = mapper;
        this.limits = limits;
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
        Profile profile = new Profile(
                request.name().trim(),
                trimToNull(request.description()),
                normalizeTags(request.tags()),
                request.payload(),
                checkedSize(request),
                checkedTemplate(request.template()));
        // Flush so the generated id, timestamps and version are in the entity before it is mapped.
        return mapper.toResponse(repository.saveAndFlush(profile));
    }

    @Transactional
    public ProfileResponse update(UUID id, ProfileRequest request) {
        Profile profile = repository.findById(id).orElseThrow(() -> new ProfileNotFoundException(id));
        profile.apply(
                request.name().trim(),
                trimToNull(request.description()),
                normalizeTags(request.tags()),
                request.payload(),
                checkedSize(request),
                checkedTemplate(request.template()));
        // Flush so the timestamps and version are in the entity before it is mapped.
        return mapper.toResponse(repository.saveAndFlush(profile));
    }

    @Transactional
    public void delete(UUID id) {
        if (!repository.existsById(id)) {
            throw new ProfileNotFoundException(id);
        }
        repository.deleteById(id);
    }

    public ProfileStats stats() {
        // max() over no rows is null, which is exactly what "never changed" should read as.
        return new ProfileStats(repository.count(), repository.totalBytes(), repository.lastUpdatedAt());
    }

    /**
     * Checks the inputs and returns their stored size. The inputs are a set of named documents —
     * one per system the scenario feeds — so an object with at least one entry is the only shape
     * that makes sense.
     */
    private int checkedSize(ProfileRequest request) {
        JsonNode payload = request.payload();
        if (!payload.isObject() || payload.isEmpty()) {
            throw new InvalidDocumentsException("The inputs must name at least one document, for example {\"main\": {...}}");
        }
        payload.fieldNames().forEachRemaining(name -> {
            if (name.isBlank()) {
                throw new InvalidDocumentsException("Every document needs a name");
            }
        });

        int size = mapper.sizeOf(payload);
        if (size > limits.maxPayloadBytes()) {
            throw new PayloadTooLargeException(size, limits.maxPayloadBytes());
        }
        return size;
    }

    /**
     * A template, when one is sent, is the selection a profile was composed from plus the values that
     * were typed: {@code {"selection": {group: fragmentId}, "values": {key: value}}}. Anything else is
     * refused — it would be stored as-is, handed back to the form, and break it. It is bound by the
     * same size limit as the inputs, since it is stored next to them.
     */
    private JsonNode checkedTemplate(JsonNode template) {
        if (template == null || template.isNull()) {
            return null;
        }
        if (!template.isObject() || !template.path("selection").isObject() || !template.path("values").isObject()) {
            throw new InvalidTemplateException("The template must be an object with a 'selection' and 'values'");
        }
        template.path("selection").fields().forEachRemaining(entry -> {
            if (!entry.getValue().isTextual()) {
                throw new InvalidTemplateException("The template selection for '" + entry.getKey() + "' must be a fragment id");
            }
        });
        int size = mapper.sizeOf(template);
        if (size > limits.maxPayloadBytes()) {
            throw new PayloadTooLargeException(size, limits.maxPayloadBytes());
        }
        return template;
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
