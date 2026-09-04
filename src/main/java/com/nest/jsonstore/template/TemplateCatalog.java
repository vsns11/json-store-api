package com.nest.jsonstore.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

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

        Set<String> groupIds = new HashSet<>();
        for (JsonNode group : groups) {
            String id = group.path("id").asText("");
            if (id.isBlank() || !groupIds.add(id)) {
                throw new IllegalStateException("Every template group needs a unique id; '" + id + "' is not one");
            }
        }
        Set<String> systems = new HashSet<>();
        catalog.path("documents").forEach(document -> systems.add(document.path("id").asText("")));

        Set<String> fragmentIds = new HashSet<>();
        for (JsonNode fragment : fragments) {
            String id = fragment.path("id").asText("");
            String group = fragment.path("group").asText("");
            if (id.isBlank() || group.isBlank()) {
                throw new IllegalStateException("Template fragment '" + id + "' needs an id and a group");
            }
            if (!fragmentIds.add(id)) {
                throw new IllegalStateException("Template fragment id '" + id + "' is used more than once");
            }
            if (!groupIds.contains(group)) {
                throw new IllegalStateException("Template fragment '" + id + "' belongs to unknown group '" + group + "'");
            }

            // A fragment writes into one document per system it feeds, keyed by the system's name.
            JsonNode documents = fragment.path("documents");
            if (!documents.isObject() || documents.isEmpty()) {
                throw new IllegalStateException("Template fragment '" + id + "' needs at least one document");
            }
            documents.fields().forEachRemaining(entry -> {
                if (!entry.getValue().isObject()) {
                    throw new IllegalStateException(
                            "Template fragment '" + id + "' writes a non-object into '" + entry.getKey() + "'");
                }
                // The systems list is what gives a document its label; a fragment feeding a system
                // nobody declared is almost always a typo in one place or the other.
                if (!systems.isEmpty() && !systems.contains(entry.getKey())) {
                    throw new IllegalStateException(
                            "Template fragment '" + id + "' writes into undeclared system '" + entry.getKey() + "'");
                }
            });
        }
    }
}
