package com.nest.jsonstore.profile.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** What the client sends when creating or updating a profile. */
public record ProfileRequest(

        @NotBlank(message = "The profile needs a name")
        @Size(max = 120, message = "Name must be at most 120 characters")
        String name,

        @Size(max = 500, message = "Description must be at most 500 characters")
        String description,

        List<@Size(max = 40, message = "A tag must be at most 40 characters") String> tags,

        @NotNull(message = "The profile needs its inputs")
        JsonNode payload
) {
}
