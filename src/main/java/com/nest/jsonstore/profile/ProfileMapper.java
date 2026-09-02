package com.nest.jsonstore.profile;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nest.jsonstore.profile.dto.ProfileResponse;
import com.nest.jsonstore.profile.dto.ProfileSummary;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
class ProfileMapper {

    private static final int PREVIEW_LENGTH = 180;

    private final ObjectMapper objectMapper;

    ProfileMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    ProfileResponse toResponse(Profile profile) {
        return new ProfileResponse(
                profile.getId(),
                profile.getName(),
                profile.getDescription(),
                profile.getTags(),
                profile.getPayload(),
                profile.getTemplate(),
                profile.getSizeBytes(),
                profile.getVersion(),
                profile.getCreatedAt(),
                profile.getUpdatedAt());
    }

    ProfileSummary toSummary(Profile profile) {
        return new ProfileSummary(
                profile.getId(),
                profile.getName(),
                profile.getDescription(),
                profile.getTags(),
                preview(profile.getPayload()),
                profile.getSizeBytes(),
                profile.getCreatedAt(),
                profile.getUpdatedAt());
    }

    /** Byte size of the inputs once minified — what PostgreSQL effectively stores. */
    int sizeOf(JsonNode payload) {
        return minify(payload).getBytes(StandardCharsets.UTF_8).length;
    }

    private String preview(JsonNode payload) {
        String minified = minify(payload);
        return minified.length() <= PREVIEW_LENGTH ? minified : minified.substring(0, PREVIEW_LENGTH) + "…";
    }

    private String minify(JsonNode payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            // The payload was parsed by Jackson on the way in, so it can always be written back out.
            throw new IllegalStateException("Could not serialize JSON payload", e);
        }
    }
}
