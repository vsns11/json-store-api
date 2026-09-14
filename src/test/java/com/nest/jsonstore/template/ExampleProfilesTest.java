package com.nest.jsonstore.template;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nest.jsonstore.error.InvalidInputsException;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The seeded examples are saved through the same checks as a user's save, and skipped when they do
 * not fit the catalogue in use. That would also silently skip an example with a typo in it, so each
 * one is checked here against both shipped catalogues: it must fit exactly one.
 */
class ExampleProfilesTest {

    private static final List<String> CATALOGUES = List.of("templates/catalog.json", "templates/tmf702-catalog.json");

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void everyExampleFitsExactlyOneShippedCatalogue() throws Exception {
        Map<String, TemplateInputs> inputs = new LinkedHashMap<>();
        for (String path : CATALOGUES) {
            TemplateCatalog catalog = new TemplateCatalog(json, new ClassPathResource(path));
            inputs.put(path, new TemplateInputs(catalog, new TemplateComposer(catalog, json), json));
        }

        JsonNode examples;
        try (InputStream stream = new ClassPathResource("examples/profiles.json").getInputStream()) {
            examples = json.readTree(stream);
        }

        List<String> problems = new ArrayList<>();
        Map<String, Integer> perCatalogue = new LinkedHashMap<>();
        for (JsonNode example : examples) {
            Map<String, String> selection = json.convertValue(example.at("/template/selection"), new TypeReference<>() {
            });
            Map<String, JsonNode> values = new LinkedHashMap<>();
            example.at("/template/values").fields().forEachRemaining(entry -> values.put(entry.getKey(), entry.getValue()));

            List<String> fits = new ArrayList<>();
            List<String> refusals = new ArrayList<>();
            inputs.forEach((path, catalogue) -> {
                try {
                    catalogue.build(selection, values);
                    fits.add(path);
                } catch (InvalidInputsException refused) {
                    refusals.add(path + " " + refused.issues());
                }
            });
            if (fits.size() == 1) {
                perCatalogue.merge(fits.getFirst(), 1, Integer::sum);
            } else {
                problems.add(example.path("name").asText() + " fits " + fits + "; refused by " + refusals);
            }
        }

        assertThat(problems).as("examples that do not fit exactly one catalogue").isEmpty();
        assertThat(perCatalogue.keySet()).as("every catalogue has examples").containsExactlyInAnyOrderElementsOf(CATALOGUES);
    }
}
