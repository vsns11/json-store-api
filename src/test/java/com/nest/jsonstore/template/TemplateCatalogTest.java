package com.nest.jsonstore.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Catalogues are data the whole product trusts, so they are tested as data: every combination a
 * user could pick is composed and checked, and each way a catalogue can be wrong is refused at load.
 * No Spring context and no database, so this runs in well under a second.
 */
class TemplateCatalogTest {

    private final ObjectMapper json = new ObjectMapper();

    private TemplateCatalog shipped(String path) {
        return new TemplateCatalog(json, new ClassPathResource(path));
    }

    private TemplateCatalog inline(String body) {
        return new TemplateCatalog(json, new ByteArrayResource(body.getBytes(StandardCharsets.UTF_8)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"templates/catalog.json", "templates/tmf702-catalog.json"})
    void everyCombinationAUserCanPickComposesWithNothingLeftUnfilled(String path) {
        TemplateCatalog catalog = shipped(path);
        TemplateComposer composer = new TemplateComposer(catalog, json);

        List<String> groupIds = new ArrayList<>();
        List<List<String>> choices = new ArrayList<>();
        for (JsonNode group : catalog.groups()) {
            String id = group.path("id").asText();
            List<String> options = new ArrayList<>();
            catalog.asJson().path("fragments").forEach(fragment -> {
                if (fragment.path("group").asText().equals(id)) {
                    options.add(fragment.path("id").asText());
                }
            });
            if (!group.path("required").asBoolean(false)) {
                options.add("");
            }
            groupIds.add(id);
            choices.add(options);
        }

        List<String> unfilled = new ArrayList<>();
        int[] combinations = {0};
        walk(0, new LinkedHashMap<>(), groupIds, choices, selection -> {
            combinations[0]++;
            String output = composer.compose(selection, samplesForFieldsWithoutDefaults(catalog, selection))
                    .documents().toString();
            int at = output.indexOf("${");
            if (at >= 0) {
                unfilled.add(selection + " -> " + output.substring(at, Math.min(output.length(), at + 30)));
            }
        });

        assertThat(combinations[0]).isPositive();
        assertThat(unfilled).as("combinations that stored a placeholder unfilled").isEmpty();
    }

    @Test
    void refusesAPlaceholderTheFragmentUsingItDoesNotDeclare() {
        // The shape of the defect that put "${sku}" into a seeded profile.
        assertThatThrownBy(() -> inline(catalogue("""
                "fields": [{"key": "warehouse", "type": "text"}],
                "documents": {"main": {"warehouse": "${warehouse}", "sku": "${sku}"}}""")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("${sku}")
                .hasMessageContaining("does not declare it");
    }

    @Test
    void refusesAFieldTypeTheFormCannotDraw() {
        assertThatThrownBy(() -> inline(catalogue("""
                "fields": [{"key": "shade", "type": "colour"}],
                "documents": {"main": {"shade": "${shade}"}}""")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'colour'");
    }

    @Test
    void refusesAChoiceWithNothingToChooseFrom() {
        assertThatThrownBy(() -> inline(catalogue("""
                "fields": [{"key": "state", "type": "select"}],
                "documents": {"main": {"state": "${state}"}}""")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no options");
    }

    @Test
    void refusesAFieldDeclaredTwiceInOneFragment() {
        assertThatThrownBy(() -> inline(catalogue("""
                "fields": [{"key": "a", "type": "text"}, {"key": "a", "type": "number"}],
                "documents": {"main": {"a": "${a}"}}""")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("more than once");
    }

    @Test
    void refusesATemplateChosenUnderAGroupItDoesNotBelongTo() {
        TemplateComposer composer = new TemplateComposer(inline("""
                {"groups": [{"id": "resource"}, {"id": "site"}],
                 "fragments": [
                   {"id": "onu", "group": "resource", "documents": {"main": {"type": "onu"}}},
                   {"id": "toronto", "group": "site", "documents": {"main": {"site": "tor"}}}]}"""), json);

        assertThatThrownBy(() -> composer.compose(Map.of("site", "onu"), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("belongs to group 'resource'");
        assertThatThrownBy(() -> composer.compose(Map.of("resource", "olt"), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no template 'olt'");
        assertThatThrownBy(() -> composer.compose(Map.of("rack", "onu"), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no template group 'rack'");
    }

    @Test
    void aValueInsideALongerStringReadsAsJsonAndANullAsNothing() {
        TemplateComposer composer = new TemplateComposer(inline(catalogue("""
                "fields": [{"key": "servers", "type": "tags"}, {"key": "note", "type": "text"}],
                "documents": {"main": {"summary": "dns=${servers}", "label": "note:${note}", "raw": "${note}"}}""")), json);

        JsonNode main = composer.compose(Map.of("g", "f"), mapOf("servers", List.of("10.0.0.1", "10.0.0.2"), "note", null))
                .documents().path("main");

        assertThat(main.path("summary").asText()).isEqualTo("dns=[\"10.0.0.1\",\"10.0.0.2\"]");
        assertThat(main.path("label").asText()).isEqualTo("note:");
        // A placeholder standing alone keeps the value's type, so null stays null rather than "".
        assertThat(main.path("raw").isNull()).isTrue();
    }

    @Test
    void ignoresValuesForFieldsNoChosenTemplateDeclares() {
        TemplateComposer composer = new TemplateComposer(inline(catalogue("""
                "fields": [{"key": "a", "type": "text", "default": "x"}],
                "documents": {"main": {"a": "${a}"}}""")), json);

        JsonNode values = composer.compose(Map.of("g", "f"), Map.of("a", "y", "smuggled", "z")).values();

        assertThat(values.path("a").asText()).isEqualTo("y");
        assertThat(values.has("smuggled")).isFalse();
    }

    /** A one-group, one-fragment catalogue around the given fragment body. */
    private static String catalogue(String fragmentBody) {
        return """
                {"groups": [{"id": "g", "required": true}],
                 "fragments": [{"id": "f", "group": "g", %s}]}""".formatted(fragmentBody);
    }

    private static Map<String, Object> mapOf(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> map = new HashMap<>();
        map.put(k1, v1);
        map.put(k2, v2);
        return map;
    }

    private static void walk(int index, Map<String, String> selection, List<String> groupIds,
                             List<List<String>> choices, Consumer<Map<String, String>> visit) {
        if (index == groupIds.size()) {
            visit.accept(new LinkedHashMap<>(selection));
            return;
        }
        for (String choice : choices.get(index)) {
            selection.put(groupIds.get(index), choice);
            walk(index + 1, selection, groupIds, choices, visit);
        }
        selection.remove(groupIds.get(index));
    }

    /** What a user would type into each field that has no default, so a composition is complete. */
    private static Map<String, Object> samplesForFieldsWithoutDefaults(TemplateCatalog catalog, Map<String, String> selection) {
        Map<String, Object> values = new HashMap<>();
        selection.values().stream()
                .filter(id -> !id.isBlank())
                .map(id -> catalog.fragment(id).orElseThrow())
                .forEach(fragment -> fragment.path("fields").forEach(field -> {
                    if (field.has("default")) {
                        return;
                    }
                    String key = field.path("key").asText();
                    values.put(key, switch (field.path("type").asText("text")) {
                        case "number", "range" -> field.path("min").asInt(1);
                        case "date" -> "2026-01-01";
                        case "select", "radio" -> optionValue(field.path("options").get(0));
                        case "checkboxes", "tags" -> List.of();
                        case "switch", "checkbox" -> false;
                        default -> "sample-" + key;
                    });
                }));
        return values;
    }

    private static String optionValue(JsonNode option) {
        return option.isObject() ? option.path("value").asText() : option.asText();
    }
}
