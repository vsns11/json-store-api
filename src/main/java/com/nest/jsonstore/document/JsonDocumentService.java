package com.nest.jsonstore.document;

import com.nest.jsonstore.config.LimitsProperties;
import com.nest.jsonstore.document.dto.JsonDocumentRequest;
import com.nest.jsonstore.document.dto.JsonDocumentResponse;
import com.nest.jsonstore.document.dto.JsonDocumentSummary;
import com.nest.jsonstore.document.dto.PageResponse;
import com.nest.jsonstore.document.dto.StoreStats;
import com.nest.jsonstore.error.DocumentNotFoundException;
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
public class JsonDocumentService {

    /** API sort keys mapped onto real columns, so the sort parameter can never reach SQL unchecked. */
    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "name", "name",
            "createdAt", "created_at",
            "updatedAt", "updated_at",
            "sizeBytes", "size_bytes");

    private final JsonDocumentRepository repository;
    private final JsonDocumentMapper mapper;
    private final LimitsProperties limits;

    JsonDocumentService(JsonDocumentRepository repository, JsonDocumentMapper mapper, LimitsProperties limits) {
        this.repository = repository;
        this.mapper = mapper;
        this.limits = limits;
    }

    public PageResponse<JsonDocumentSummary> list(String search, int page, int size, String sort, String direction) {
        return PageResponse.of(
                repository.search(StringUtils.hasText(search) ? search.trim() : null, pageable(page, size, sort, direction)),
                mapper::toSummary);
    }

    public JsonDocumentResponse get(UUID id) {
        return repository.findById(id)
                .map(mapper::toResponse)
                .orElseThrow(() -> new DocumentNotFoundException(id));
    }

    @Transactional
    public JsonDocumentResponse create(JsonDocumentRequest request) {
        JsonDocument document = new JsonDocument(
                request.name().trim(),
                trimToNull(request.description()),
                normalizeTags(request.tags()),
                request.payload(),
                checkedSize(request));
        // Flush so the generated id, timestamps and version are in the entity before it is mapped.
        return mapper.toResponse(repository.saveAndFlush(document));
    }

    @Transactional
    public JsonDocumentResponse update(UUID id, JsonDocumentRequest request) {
        JsonDocument document = repository.findById(id).orElseThrow(() -> new DocumentNotFoundException(id));
        document.apply(
                request.name().trim(),
                trimToNull(request.description()),
                normalizeTags(request.tags()),
                request.payload(),
                checkedSize(request));
        // Flush so the generated id, timestamps and version are in the entity before it is mapped.
        return mapper.toResponse(repository.saveAndFlush(document));
    }

    @Transactional
    public void delete(UUID id) {
        if (!repository.existsById(id)) {
            throw new DocumentNotFoundException(id);
        }
        repository.deleteById(id);
    }

    public StoreStats stats() {
        return new StoreStats(repository.count(), repository.totalBytes(), lastUpdatedAt());
    }

    private Instant lastUpdatedAt() {
        return repository.count() == 0 ? null : repository.lastUpdatedAt();
    }

    /** Sizes the payload once and refuses anything over the configured limit. */
    private int checkedSize(JsonDocumentRequest request) {
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
