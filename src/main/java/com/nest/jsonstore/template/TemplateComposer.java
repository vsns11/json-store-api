package com.nest.jsonstore.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

import static com.nest.jsonstore.template.TemplateCatalog.PLACEHOLDER;

/**
 * Builds a profile's documents from a template selection: each chosen fragment contributes to every
 * system it names, `${field}` placeholders are filled in, and a string that is only a placeholder
 * keeps the value's own type.
 *
 * It refuses a selection the form would never offer — a template the catalogue does not have, or
 * one chosen under a group it does not belong to — rather than quietly composing without it.
 */
@Component
public class TemplateComposer {

    private final TemplateCatalog catalog;
    private final ObjectMapper json;

    TemplateComposer(TemplateCatalog catalog, ObjectMapper json) {
        this.catalog = catalog;
        this.json = json;
    }

    /**
     * @param selection fragment id per group, e.g. {@code {"scenario": "checkout"}}; a blank id leaves the group unset
     * @param overrides values for the chosen fragments' fields; a key no chosen fragment declares is ignored
     * @throws IllegalArgumentException for a group or template the catalogue does not have, or a
     *                                  template chosen under a group it does not belong to
     */
    public Composition compose(Map<String, String> selection, Map<String, ?> overrides) {
        List<JsonNode> chosen = chosen(selection);
        Map<String, JsonNode> values = valuesFor(chosen, overrides);
        ObjectNode documents = json.createObjectNode();

        for (JsonNode fragment : chosen) {
            fragment.path("documents").fields().forEachRemaining(entry ->
                    documents.set(entry.getKey(), merge(documents.get(entry.getKey()), substitute(entry.getValue(), values))));
        }

        ObjectNode storedValues = json.createObjectNode();
        values.forEach(storedValues::set);
        return new Composition(documents, storedValues);
    }

    /** The documents to store, and the values that produced them. */
    public record Composition(JsonNode documents, JsonNode values) {
    }

    /** The chosen fragments in catalogue group order, refusing anything the form would not offer. */
    List<JsonNode> chosen(Map<String, String> selection) {
        selection.forEach((groupId, fragmentId) -> {
            if (fragmentId == null || fragmentId.isBlank()) {
                return;
            }
            if (catalog.group(groupId).isEmpty()) {
                throw new IllegalArgumentException("There is no template group '" + groupId + "'");
            }
            JsonNode fragment = catalog.fragment(fragmentId)
                    .orElseThrow(() -> new IllegalArgumentException("There is no template '" + fragmentId + "'"));
            String owner = fragment.path("group").asText();
            if (!owner.equals(groupId)) {
                throw new IllegalArgumentException(
                        "Template '" + fragmentId + "' belongs to group '" + owner + "', not '" + groupId + "'");
            }
        });

        List<JsonNode> fragments = new ArrayList<>();
        for (JsonNode group : catalog.groups()) {
            String wanted = selection.get(group.path("id").asText());
            if (wanted != null && !wanted.isBlank()) {
                catalog.fragment(wanted).ifPresent(fragments::add);
            }
        }
        return fragments;
    }

    /** Every field the chosen fragments declare, once each, first declaration first. */
    static Map<String, JsonNode> fieldsOf(List<JsonNode> chosen) {
        Map<String, JsonNode> fields = new LinkedHashMap<>();
        for (JsonNode fragment : chosen) {
            for (JsonNode field : fragment.path("fields")) {
                fields.putIfAbsent(field.path("key").asText(), field);
            }
        }
        return fields;
    }

    /**
     * Defaults for the declared fields, with the overrides applied. When two fragments declare the
     * same key, the first declaration that carries a default supplies it, so one fragment without a
     * default cannot hide another fragment's.
     */
    private Map<String, JsonNode> valuesFor(List<JsonNode> chosen, Map<String, ?> overrides) {
        Map<String, JsonNode> values = new LinkedHashMap<>();
        for (JsonNode fragment : chosen) {
            for (JsonNode field : fragment.path("fields")) {
                String key = field.path("key").asText();
                if (!values.containsKey(key) && field.has("default")) {
                    values.put(key, field.get("default"));
                }
            }
        }
        Map<String, JsonNode> declared = fieldsOf(chosen);
        overrides.forEach((key, value) -> {
            if (declared.containsKey(key)) {
                values.put(key, value == null ? NullNode.getInstance()
                        : value instanceof JsonNode node ? node : json.valueToTree(value));
            }
        });
        return values;
    }

    private JsonNode substitute(JsonNode node, Map<String, JsonNode> values) {
        if (node.isObject()) {
            ObjectNode result = json.createObjectNode();
            node.fields().forEachRemaining(entry -> result.set(entry.getKey(), substitute(entry.getValue(), values)));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = json.createArrayNode();
            node.forEach(item -> result.add(substitute(item, values)));
            return result;
        }
        if (!node.isTextual()) {
            return node;
        }

        String text = node.asText();
        Matcher whole = PLACEHOLDER.matcher(text);
        if (whole.matches()) {
            // The only content is a placeholder, so the value keeps its own type — null included.
            return values.containsKey(whole.group(1)) ? values.get(whole.group(1)) : node;
        }

        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder filled = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String replacement = values.containsKey(key) ? inline(values.get(key)) : matcher.group();
            matcher.appendReplacement(filled, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(filled);
        return json.getNodeFactory().textNode(filled.toString());
    }

    /**
     * How a value reads inside a longer string: text as itself, null as nothing, and a list or object
     * as JSON. {@code asText()} alone would print a list or an object as an empty string.
     */
    private static String inline(JsonNode value) {
        if (value == null || value.isNull()) {
            return "";
        }
        return value.isValueNode() ? value.asText() : value.toString();
    }

    /** Objects merge key by key; lists are appended, so two fragments can both add to one. */
    private JsonNode merge(JsonNode base, JsonNode addition) {
        if (base == null) {
            return addition;
        }
        if (base.isObject() && addition.isObject()) {
            ObjectNode merged = ((ObjectNode) base).deepCopy();
            addition.fields().forEachRemaining(entry ->
                    merged.set(entry.getKey(), merge(merged.get(entry.getKey()), entry.getValue())));
            return merged;
        }
        if (base.isArray() && addition.isArray()) {
            ArrayNode merged = ((ArrayNode) base).deepCopy();
            addition.forEach(merged::add);
            return merged;
        }
        return addition;
    }
}
