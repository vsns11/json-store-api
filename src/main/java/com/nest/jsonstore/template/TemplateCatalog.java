package com.nest.jsonstore.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The catalogue of JSON fragments the composer offers. Read once at startup and checked, so a
 * malformed catalogue fails the deployment rather than a user's first click.
 */
@Component
public class TemplateCatalog {

    /** A {@code ${name}} token inside a fragment body. */
    static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([\\w.]+)}");

    /**
     * Every field type the form knows how to draw. Anything else would be drawn as a plain text box,
     * which would then store a string where the catalogue author meant a number or a list.
     */
    static final Set<String> FIELD_TYPES = Set.of(
            "text", "textarea", "number", "range", "date", "select", "radio",
            "switch", "checkbox", "checkboxes", "tags");

    private static final Set<String> CHOICE_TYPES = Set.of("select", "radio", "checkboxes");

    private final JsonNode catalog;
    private final Map<String, JsonNode> groupsById = new LinkedHashMap<>();
    private final Map<String, JsonNode> fragmentsById = new LinkedHashMap<>();

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

    /** The groups in the order the form shows them, which is also the order fragments merge in. */
    public JsonNode groups() {
        return catalog.path("groups");
    }

    public Optional<JsonNode> group(String id) {
        return Optional.ofNullable(groupsById.get(id));
    }

    public Optional<JsonNode> fragment(String id) {
        return Optional.ofNullable(fragmentsById.get(id));
    }

    private void validate() {
        JsonNode groups = catalog.path("groups");
        JsonNode fragments = catalog.path("fragments");
        if (!groups.isArray() || groups.isEmpty() || !fragments.isArray() || fragments.isEmpty()) {
            throw new IllegalStateException("The template catalogue needs a non-empty 'groups' and 'fragments'");
        }

        for (JsonNode group : groups) {
            String id = group.path("id").asText("");
            if (id.isBlank() || groupsById.putIfAbsent(id, group) != null) {
                throw new IllegalStateException("Every template group needs a unique id; '" + id + "' is not one");
            }
        }
        Set<String> systems = new HashSet<>();
        catalog.path("documents").forEach(document -> systems.add(document.path("id").asText("")));

        for (JsonNode fragment : fragments) {
            String id = fragment.path("id").asText("");
            String group = fragment.path("group").asText("");
            if (id.isBlank() || group.isBlank()) {
                throw new IllegalStateException("Template fragment '" + id + "' needs an id and a group");
            }
            if (fragmentsById.putIfAbsent(id, fragment) != null) {
                throw new IllegalStateException("Template fragment id '" + id + "' is used more than once");
            }
            if (!groupsById.containsKey(group)) {
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

            Set<String> declared = validateFields(id, fragment.path("fields"));

            // A placeholder no field declares is never asked for, so the form cannot fill it, the
            // required-field check cannot flag it, and the literal "${sku}" is stored instead. That is
            // exactly how two seeded profiles came to carry unfilled text; refuse it at load.
            Set<String> used = new TreeSet<>();
            collectPlaceholders(documents, used);
            used.removeAll(declared);
            if (!used.isEmpty()) {
                throw new IllegalStateException("Template fragment '" + id + "' uses "
                        + used.stream().map(key -> "${" + key + "}").collect(Collectors.joining(", "))
                        + " but does not declare " + (used.size() == 1 ? "it" : "them") + " as a field. "
                        + "Every placeholder must be declared by the fragment that uses it.");
            }
        }
    }

    /** Checks one fragment's field declarations and returns the keys it declares. */
    private static Set<String> validateFields(String fragmentId, JsonNode fields) {
        Set<String> keys = new HashSet<>();
        if (fields.isMissingNode() || fields.isNull()) {
            return keys;
        }
        if (!fields.isArray()) {
            throw new IllegalStateException("Template fragment '" + fragmentId + "' has 'fields' that is not a list");
        }
        for (JsonNode field : fields) {
            String key = field.path("key").asText("");
            if (key.isBlank()) {
                throw new IllegalStateException("A field in template fragment '" + fragmentId + "' has no key");
            }
            if (!keys.add(key)) {
                throw new IllegalStateException(
                        "Template fragment '" + fragmentId + "' declares the field '" + key + "' more than once");
            }
            String type = field.path("type").asText("text");
            if (!FIELD_TYPES.contains(type)) {
                throw new IllegalStateException("Field '" + key + "' in template fragment '" + fragmentId
                        + "' has type '" + type + "', which the form cannot draw. Known types: " + new TreeSet<>(FIELD_TYPES));
            }
            if (CHOICE_TYPES.contains(type) && (!field.path("options").isArray() || field.path("options").isEmpty())) {
                throw new IllegalStateException("Field '" + key + "' in template fragment '" + fragmentId
                        + "' is a " + type + " with no options to choose from");
            }
        }
        return keys;
    }

    private static void collectPlaceholders(JsonNode node, Set<String> into) {
        if (node.isTextual()) {
            Matcher matcher = PLACEHOLDER.matcher(node.asText());
            while (matcher.find()) {
                into.add(matcher.group(1));
            }
        } else if (node.isContainerNode()) {
            node.forEach(child -> collectPlaceholders(child, into));
        }
    }
}
