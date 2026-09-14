package com.nest.jsonstore.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nest.jsonstore.error.InvalidInputsException;
import com.nest.jsonstore.profile.dto.ProfileRequest;
import com.nest.jsonstore.profile.dto.TemplateRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Puts a few example profiles in an empty database so a fresh environment has something to show.
 *
 * They are saved through the same service a user's save goes through, so the server composes and
 * checks them against the catalogue. The seeder used to write straight to the repository, which is
 * how one example came to store the literal text "${sku}" without anything noticing.
 *
 * Runs only under the {@code local} profile, and never touches a database that already has data.
 */
@Configuration
@org.springframework.context.annotation.Profile("local")
@ConditionalOnProperty(name = "app.seed-examples", havingValue = "true", matchIfMissing = true)
class ExampleDataSeeder {

    private static final Logger log = LoggerFactory.getLogger(ExampleDataSeeder.class);

    /** name, description, tags, which template per group, and the values that differ from the defaults. */
    private record Example(String name, String description, List<String> tags,
                           Map<String, String> selection, Map<String, Object> values) {
    }

    private static final List<Example> EXAMPLES = List.of(
            new Example("Checkout — happy path",
                    "Card clears, stock is reserved, both notifications go out",
                    List.of("checkout", "smoke"),
                    Map.of("scenario", "checkout", "customer", "returning-customer",
                            "payment", "card-approved", "fulfilment", "stock-reserved",
                            "notification", "email-and-sms", "expectations", "expect-success"),
                    Map.of("scenarioName", "Checkout — happy path", "orderRef", "ORD-10042")),

            new Example("Checkout — expired card",
                    "The issuer refuses the charge, so nothing ships",
                    List.of("checkout", "negative"),
                    Map.of("scenario", "checkout", "customer", "returning-customer",
                            "payment", "card-declined", "notification", "email-only",
                            "expectations", "expect-failure"),
                    Map.of("scenarioName", "Checkout — expired card", "orderRef", "ORD-10043",
                            "declineCode", "expired_card")),

            new Example("Bulk import — 10k rows",
                    "A file pushed through the importer overnight",
                    List.of("import", "load"),
                    Map.of("scenario", "bulk-import", "expectations", "expect-success"),
                    Map.of("scenarioName", "Bulk import — 10k rows", "rows", 10000, "batchSize", 500)),

            new Example("Renewal — backordered item",
                    "A renewal for a plan whose item is out of stock",
                    List.of("subscription", "edge"),
                    Map.of("scenario", "subscription-renewal", "customer", "new-customer",
                            "payment", "bank-transfer", "fulfilment", "backorder",
                            "expectations", "expect-success"),
                    Map.of("scenarioName", "Renewal — backordered item", "planCode", "TEAM-YEARLY")));

    @Bean
    ApplicationRunner seedExamples(ProfileRepository repository, ProfileService profiles, ObjectMapper json) {
        return args -> {
            if (repository.count() > 0) {
                return;
            }

            int seeded = 0;
            for (Example example : EXAMPLES) {
                Map<String, JsonNode> values = new LinkedHashMap<>();
                example.values().forEach((key, value) -> values.put(key, json.valueToTree(value)));
                try {
                    profiles.create(new ProfileRequest(example.name(), example.description(), example.tags(),
                            new TemplateRequest(example.selection(), values)));
                    seeded++;
                } catch (InvalidInputsException mismatch) {
                    // Written against the scenario catalogue. Pointed at another one — the TMF702
                    // catalogue, say — they do not fit, and are skipped rather than stored half-built.
                    log.info("Skipping example '{}': {}", example.name(), mismatch.getMessage());
                }
            }
            if (seeded > 0) {
                log.info("Seeded {} example profiles through the same checks a user's save goes through", seeded);
            }
        };
    }
}
