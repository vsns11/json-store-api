package com.nest.jsonstore.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nest.jsonstore.error.ApiError.FieldIssue;
import com.nest.jsonstore.error.InvalidInputsException;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns what a user chose and typed into the inputs that are stored — and is the only thing that
 * does. The browser composes a preview as you type, but it does not get to decide what is saved: a
 * client could send any JSON, and the two composers have disagreed before. So the server checks the
 * selection and every value against the catalogue, composes the documents itself, and stores its own
 * result.
 */
@Component
public class TemplateInputs {

    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private final TemplateCatalog catalog;
    private final TemplateComposer composer;
    private final ObjectMapper json;

    TemplateInputs(TemplateCatalog catalog, TemplateComposer composer, ObjectMapper json) {
        this.catalog = catalog;
        this.composer = composer;
        this.json = json;
    }

    /** The documents a valid selection builds, and the template to store beside them. */
    public record Built(JsonNode documents, JsonNode template) {
    }

    /**
     * @param selection template id per group; a blank id leaves the group unset
     * @param values    what was typed, by field key
     * @throws InvalidInputsException listing every problem, each pointing at {@code template.selection.<group>}
     *                                or {@code template.values.<key>}
     */
    public Built build(Map<String, String> selection, Map<String, JsonNode> values) {
        Map<String, String> chosen = new LinkedHashMap<>();
        if (selection != null) {
            selection.forEach((group, fragment) -> {
                if (fragment != null && !fragment.isBlank()) {
                    chosen.put(group, fragment.trim());
                }
            });
        }
        Map<String, JsonNode> given = values == null ? Map.of() : values;

        List<FieldIssue> issues = new ArrayList<>();
        checkSelection(chosen, issues);
        if (!issues.isEmpty()) {
            throw new InvalidInputsException(issues);
        }

        List<JsonNode> fragments = composer.chosen(chosen);
        Map<String, JsonNode> declared = TemplateComposer.fieldsOf(fragments);
        Set<String> used = new HashSet<>();
        fragments.forEach(fragment -> TemplateCatalog.collectPlaceholders(fragment.path("documents"), used));
        Map<String, JsonNode> defaults = defaultsOf(fragments);

        given.keySet().stream().filter(key -> !declared.containsKey(key)).sorted().forEach(key ->
                issues.add(new FieldIssue("template.values." + key,
                        "None of the chosen templates has a field called '" + key + "'")));

        Map<String, JsonNode> resolved = new LinkedHashMap<>();
        declared.forEach((key, field) -> {
            boolean provided = given.containsKey(key);
            // A field no body substitutes changes nothing, so the form never shows it; demanding a
            // value for it would be an error the user has no way to fix.
            if (!used.contains(key) && !provided) {
                return;
            }
            JsonNode value = provided ? given.get(key) : defaults.get(key);
            if (value == null) {
                value = NullNode.getInstance();
            }
            String problem = problemWith(field, value, used.contains(key));
            if (problem != null) {
                issues.add(new FieldIssue("template.values." + key, problem));
            }
            resolved.put(key, value);
        });
        if (!issues.isEmpty()) {
            throw new InvalidInputsException(issues);
        }

        TemplateComposer.Composition composition = composer.compose(chosen, resolved);
        if (composition.documents().isEmpty()) {
            throw new InvalidInputsException("template.selection", "The chosen templates build no inputs");
        }
        // The catalogue refuses undeclared placeholders at load and every declared one was resolved
        // above, so this cannot happen — which is exactly why it is worth the check if it ever does.
        if (composition.documents().toString().contains("${")) {
            throw new InvalidInputsException("template", "The chosen templates left a placeholder unfilled");
        }

        ObjectNode template = json.createObjectNode();
        template.set("selection", json.valueToTree(chosen));
        template.set("values", composition.values());
        return new Built(composition.documents(), template);
    }

    private void checkSelection(Map<String, String> chosen, List<FieldIssue> issues) {
        chosen.forEach((groupId, fragmentId) -> {
            String at = "template.selection." + groupId;
            Optional<JsonNode> group = catalog.group(groupId);
            if (group.isEmpty()) {
                issues.add(new FieldIssue(at, "There is no template group '" + groupId + "'"));
                return;
            }
            Optional<JsonNode> fragment = catalog.fragment(fragmentId);
            if (fragment.isEmpty()) {
                issues.add(new FieldIssue(at, "There is no template '" + fragmentId + "' for " + label(group.get())));
            } else if (!fragment.get().path("group").asText().equals(groupId)) {
                issues.add(new FieldIssue(at, "'" + fragmentId + "' is not a template for " + label(group.get())));
            }
        });
        for (JsonNode group : catalog.groups()) {
            String id = group.path("id").asText();
            if (group.path("required").asBoolean(false) && !chosen.containsKey(id)) {
                issues.add(new FieldIssue("template.selection." + id, "Choose a template for " + label(group)));
            }
        }
    }

    /** The first declaration of each key that carries a default supplies it, as the composer does. */
    private static Map<String, JsonNode> defaultsOf(List<JsonNode> fragments) {
        Map<String, JsonNode> defaults = new LinkedHashMap<>();
        for (JsonNode fragment : fragments) {
            for (JsonNode field : fragment.path("fields")) {
                if (field.has("default")) {
                    defaults.putIfAbsent(field.path("key").asText(), field.get("default"));
                }
            }
        }
        return defaults;
    }

    /** What is wrong with one value, in words for the person who typed it, or null when nothing is. */
    static String problemWith(JsonNode field, JsonNode value, boolean used) {
        String label = field.path("label").asText(field.path("key").asText());
        boolean blank = value.isNull()
                || (value.isTextual() && value.asText().isBlank())
                || (value.isArray() && value.isEmpty());
        if (blank) {
            return used && field.path("required").asBoolean(false) ? label + " is required" : null;
        }

        switch (field.path("type").asText("text")) {
            case "text", "textarea" -> {
                if (!value.isTextual()) {
                    return label + " must be text";
                }
            }
            case "date" -> {
                if (!value.isTextual() || !DATE.matcher(value.asText()).matches()) {
                    return label + " must be a date, written as YYYY-MM-DD";
                }
                try {
                    LocalDate.parse(value.asText());
                } catch (DateTimeParseException notADay) {
                    return label + " is not a real date";
                }
            }
            case "number", "range" -> {
                if (!value.isNumber()) {
                    return label + " must be a number";
                }
                JsonNode min = field.path("min");
                JsonNode max = field.path("max");
                if (min.isNumber() && value.decimalValue().compareTo(min.decimalValue()) < 0) {
                    return label + " must be at least " + min.asText();
                }
                if (max.isNumber() && value.decimalValue().compareTo(max.decimalValue()) > 0) {
                    return label + " must be at most " + max.asText();
                }
            }
            case "select", "radio" -> {
                Set<String> allowed = optionValues(field);
                if (!value.isValueNode() || !allowed.contains(value.asText())) {
                    return label + " must be one of " + String.join(", ", allowed);
                }
            }
            case "checkboxes" -> {
                if (!value.isArray()) {
                    return label + " must be a list";
                }
                Set<String> allowed = optionValues(field);
                for (JsonNode item : value) {
                    if (!item.isValueNode() || !allowed.contains(item.asText())) {
                        return label + " can only include " + String.join(", ", allowed);
                    }
                }
            }
            case "tags" -> {
                if (!value.isArray()) {
                    return label + " must be a list";
                }
                for (JsonNode item : value) {
                    if (!item.isTextual()) {
                        return label + " must be a list of text";
                    }
                }
            }
            case "switch", "checkbox" -> {
                if (!value.isBoolean()) {
                    return label + " must be on or off";
                }
            }
            default -> {
                // TemplateCatalog refuses any other type at load.
            }
        }

        if (field.hasNonNull("pattern") && value.isTextual() && !value.asText().matches(field.path("pattern").asText())) {
            return label + " is not in the expected format";
        }
        return null;
    }

    private static Set<String> optionValues(JsonNode field) {
        Set<String> values = new LinkedHashSet<>();
        field.path("options").forEach(option -> values.add(option.isObject() ? option.path("value").asText() : option.asText()));
        return values;
    }

    private static String label(JsonNode group) {
        return group.path("label").asText(group.path("id").asText());
    }
}
