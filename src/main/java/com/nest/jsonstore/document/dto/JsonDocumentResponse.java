package com.nest.jsonstore.document.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A document with its full JSON payload. */
public record JsonDocumentResponse(
        UUID id,
        String name,
        String description,
        List<String> tags,
        JsonNode payload,
        int sizeBytes,
        long version,
        Instant createdAt,
        Instant updatedAt
) {
}
