package com.nest.jsonstore.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds a profile's documents from a template selection, the same way the browser does: each
 * chosen fragment contributes to every system it names, `${field}` placeholders are filled in, and
 * a string that is only a placeholder keeps the value's own type.
 *
 * The browser composes as you type; this exists so the server can too — the example profiles a
 * fresh database gets are generated from the catalogue rather than written out by hand, so they
 * cannot drift away from it.
 */
@Component
public class TemplateComposer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([\\w.]+)}");

    private final TemplateCatalog catalog;
    private final ObjectMapper json;

    TemplateComposer(TemplateCatalog catalog, ObjectMapper json) {
        this.catalog = catalog;
        this.json = json;
    }

    /**
     * @param selection fragment id per group, e.g. {@code {"scenario": "checkout"}}
     * @param overrides values to use instead of the fields' defaults
     */
    public Composition compose(Map<String, String> selection, Map<String, Object> overrides) {
        Map<String, JsonNode> values = valuesFor(selection, overrides);
        ObjectNode documents = json.createObjectNode();

        for (JsonNode fragment : chosen(selection)) {
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

    private java.util.List<JsonNode> chosen(Map<String, String> selection) {
        java.util.List<JsonNode> fragments = new java.util.ArrayList<>();
        for (JsonNode group : catalog.asJson().path("groups")) {
            String wanted = selection.get(group.path("id").asText());
            if (wanted == null) {
                continue;
            }
            for (JsonNode fragment : catalog.asJson().path("fragments")) {
                if (fragment.path("id").asText().equals(wanted)) {
                    fragments.add(fragment);
                }
            }
        }
        return fragments;
    }

    /** Defaults for every field the chosen fragments substitute, with the overrides applied. */
    private Map<String, JsonNode> valuesFor(Map<String, String> selection, Map<String, Object> overrides) {
        Map<String, JsonNode> values = new LinkedHashMap<>();
        for (JsonNode fragment : chosen(selection)) {
            for (JsonNode field : fragment.path("fields")) {
                String key = field.path("key").asText();
                if (!values.containsKey(key) && field.has("default")) {
                    values.put(key, field.get("default"));
                }
            }
        }
        overrides.forEach((key, value) -> values.put(key, json.valueToTree(value)));
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
            // The only content is a placeholder, so the value keeps its own type.
            return values.getOrDefault(whole.group(1), node);
        }

        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder filled = new StringBuilder();
        while (matcher.find()) {
            JsonNode value = values.get(matcher.group(1));
            matcher.appendReplacement(filled, Matcher.quoteReplacement(value == null ? matcher.group() : value.asText()));
        }
        matcher.appendTail(filled);
        return json.getNodeFactory().textNode(filled.toString());
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
