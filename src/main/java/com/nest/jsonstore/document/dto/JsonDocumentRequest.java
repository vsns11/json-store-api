package com.nest.jsonstore.document.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Payload accepted when creating or updating a document. */
public record JsonDocumentRequest(

        @NotBlank(message = "Name is required")
        @Size(max = 120, message = "Name must be at most 120 characters")
        String name,

        @Size(max = 500, message = "Description must be at most 500 characters")
        String description,

        List<@Size(max = 40, message = "A tag must be at most 40 characters") String> tags,

        @NotNull(message = "A JSON payload is required")
        JsonNode payload
) {
}
