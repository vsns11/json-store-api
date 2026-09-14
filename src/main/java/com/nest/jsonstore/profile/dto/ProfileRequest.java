package com.nest.jsonstore.profile.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * What the client sends when creating or updating a profile.
 *
 * There is deliberately no field for the inputs. They are composed by the server from the template,
 * so a client cannot store JSON the catalogue would not have produced — and a body that still sends
 * {@code payload} has it ignored.
 */
public record ProfileRequest(

        @NotBlank(message = "The profile needs a name")
        @Size(max = 120, message = "Name must be at most 120 characters")
        String name,

        @Size(max = 500, message = "Description must be at most 500 characters")
        String description,

        List<@Size(max = 40, message = "A tag must be at most 40 characters") String> tags,

        /**
         * The templates chosen and the values typed. Required to create a profile. On update, leaving it
         * out changes only the name, description and tags, and keeps the inputs exactly as they are.
         */
        TemplateRequest template
) {
}
