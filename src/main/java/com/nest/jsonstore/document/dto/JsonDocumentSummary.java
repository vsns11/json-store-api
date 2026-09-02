package com.nest.jsonstore.document.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A document without its payload — a short preview is sent instead, to keep list responses light. */
public record JsonDocumentSummary(
        UUID id,
        String name,
        String description,
        List<String> tags,
        String preview,
        int sizeBytes,
        Instant createdAt,
        Instant updatedAt
) {
}
