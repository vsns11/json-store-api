package com.nest.jsonstore.profile.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A profile without its inputs — a short preview is sent instead, to keep list responses light. */
public record ProfileSummary(
        UUID id,
        String name,
        String description,
        List<String> tags,
        /** The systems this profile feeds, one document each. */
        List<String> documents,
        String preview,
        int sizeBytes,
        Instant createdAt,
        Instant updatedAt
) {
}
