package com.nest.jsonstore.profile;

import com.nest.jsonstore.config.LimitsProperties;
import com.nest.jsonstore.profile.dto.ProfileRequest;
import com.nest.jsonstore.profile.dto.ProfileResponse;
import com.nest.jsonstore.profile.dto.ProfileSummary;
import com.nest.jsonstore.profile.dto.PageResponse;
import com.nest.jsonstore.profile.dto.ProfileStats;
import com.nest.jsonstore.error.ProfileNotFoundException;
import com.nest.jsonstore.error.PayloadTooLargeException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
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

    private final ProfileRepository repository;
    private final ProfileMapper mapper;
    private final LimitsProperties limits;

    ProfileService(ProfileRepository repository, ProfileMapper mapper, LimitsProperties limits) {
        this.repository = repository;
        this.mapper = mapper;
        this.limits = limits;
    }

    public PageResponse<ProfileSummary> list(String search, int page, int size, String sort, String direction) {
        return PageResponse.of(
                repository.search(StringUtils.hasText(search) ? search.trim() : null, pageable(page, size, sort, direction)),
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
                request.template());
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
                request.template());
        // Flush so the generated id, timestamps and version are in the entity before it is mapped.
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
        return new ProfileStats(repository.count(), repository.totalBytes(), lastUpdatedAt());
    }

    private Instant lastUpdatedAt() {
        return repository.count() == 0 ? null : repository.lastUpdatedAt();
    }

    /** Sizes the inputs once and refuses anything over the configured limit. */
    private int checkedSize(ProfileRequest request) {
        int size = mapper.sizeOf(request.payload());
        if (size > limits.maxPayloadBytes()) {
            throw new PayloadTooLargeException(size, limits.maxPayloadBytes());
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
                .limit(12)
                .toList();
    }

    private static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
