package com.nest.jsonstore.profile.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A profile with all of its inputs. */
public record ProfileResponse(
        UUID id,
        String name,
        String description,
        List<String> tags,
        JsonNode payload,
        JsonNode template,
        int sizeBytes,
        long version,
        Instant createdAt,
        Instant updatedAt
) {
}
