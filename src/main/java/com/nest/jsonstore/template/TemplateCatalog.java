package com.nest.jsonstore.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.io.InputStream;

/**
 * The catalogue of JSON fragments the composer offers. Read once at startup and checked, so a
 * malformed catalogue fails the deployment rather than a user's first click.
 */
@Component
public class TemplateCatalog {

    private final JsonNode catalog;

    TemplateCatalog(ObjectMapper objectMapper, @Value("${app.templates.catalog:classpath:templates/catalog.json}") Resource resource) {
        try (InputStream stream = resource.getInputStream()) {
            this.catalog = objectMapper.readTree(stream);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read the template catalogue from " + resource, e);
        }
        validate();
    }

    public JsonNode asJson() {
        return catalog;
    }

    private void validate() {
        JsonNode groups = catalog.path("groups");
        JsonNode fragments = catalog.path("fragments");
        if (!groups.isArray() || groups.isEmpty() || !fragments.isArray() || fragments.isEmpty()) {
            throw new IllegalStateException("The template catalogue needs a non-empty 'groups' and 'fragments'");
        }
        for (JsonNode fragment : fragments) {
            String id = fragment.path("id").asText("");
            if (id.isBlank() || fragment.path("group").asText("").isBlank() || !fragment.path("body").isObject()) {
                throw new IllegalStateException("Template fragment '" + id + "' needs an id, a group and an object body");
            }
            // Which system's document this fragment contributes to.
            if (fragment.path("target").asText("").isBlank()) {
                throw new IllegalStateException("Template fragment '" + id + "' needs a target document");
            }
        }
    }
}
