package com.nest.jsonstore.document;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nest.jsonstore.document.dto.JsonDocumentResponse;
import com.nest.jsonstore.document.dto.JsonDocumentSummary;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
class JsonDocumentMapper {

    private static final int PREVIEW_LENGTH = 180;

    private final ObjectMapper objectMapper;

    JsonDocumentMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    JsonDocumentResponse toResponse(JsonDocument document) {
        return new JsonDocumentResponse(
                document.getId(),
                document.getName(),
                document.getDescription(),
                document.getTags(),
                document.getPayload(),
                document.getSizeBytes(),
                document.getVersion(),
                document.getCreatedAt(),
                document.getUpdatedAt());
    }

    JsonDocumentSummary toSummary(JsonDocument document) {
        return new JsonDocumentSummary(
                document.getId(),
                document.getName(),
                document.getDescription(),
                document.getTags(),
                preview(document.getPayload()),
                document.getSizeBytes(),
                document.getCreatedAt(),
                document.getUpdatedAt());
    }

    /** Byte size of the payload once minified — what PostgreSQL effectively stores. */
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
