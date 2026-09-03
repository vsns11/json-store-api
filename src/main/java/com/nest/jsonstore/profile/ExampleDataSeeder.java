package com.nest.jsonstore.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nest.jsonstore.template.TemplateComposer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * Puts a few example profiles in an empty database so a fresh environment has something to show.
 * They are composed from the template catalogue rather than written out here, so they always match
 * what the form would produce and each one feeds several systems.
 *
 * Never runs under the {@code prod} profile, and never touches a database that already has data.
 */
@Configuration
@org.springframework.context.annotation.Profile("!prod")
@ConditionalOnProperty(name = "app.seed-examples", havingValue = "true", matchIfMissing = true)
class ExampleDataSeeder {

    private static final Logger log = LoggerFactory.getLogger(ExampleDataSeeder.class);

    /** name, description, tags, which fragment per group, and the values that differ from the defaults. */
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
    ApplicationRunner seedExamples(ProfileRepository repository, ProfileMapper mapper,
                                   TemplateComposer composer, ObjectMapper json) {
        return args -> {
            if (repository.count() > 0) {
                return;
            }

            List<Profile> profiles = EXAMPLES.stream().map(example -> {
                TemplateComposer.Composition composed = composer.compose(example.selection(), example.values());

                ObjectNode template = json.createObjectNode();
                template.set("selection", json.valueToTree(example.selection()));
                template.set("values", composed.values());

                return new Profile(example.name(), example.description(), example.tags(),
                        composed.documents(), mapper.sizeOf(composed.documents()), template);
            }).toList();

            repository.saveAll(profiles);
            log.info("Seeded {} example profiles, composed from the template catalogue", profiles.size());
        };
    }
}
