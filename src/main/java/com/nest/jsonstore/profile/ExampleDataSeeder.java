package com.nest.jsonstore.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Puts a few example profiles in an empty database so a fresh environment has something to show.
 * Never runs under the {@code prod} profile, and never touches a database that already has data.
 */
@Configuration
@org.springframework.context.annotation.Profile("!prod")
@ConditionalOnProperty(name = "app.seed-examples", havingValue = "true", matchIfMissing = true)
class ExampleDataSeeder {

    private static final Logger log = LoggerFactory.getLogger(ExampleDataSeeder.class);

    @Bean
    ApplicationRunner seedExamples(ProfileRepository repository, ProfileMapper mapper, ObjectMapper json) {
        return args -> {
            if (repository.count() > 0) {
                return;
            }
            List<Profile> examples = List.of(
                    example(json, mapper, "Checkout — happy path",
                            "A card that clears first time, one item in the basket",
                            List.of("checkout", "smoke"),
                            """
                            {"customer":{"id":"cus_1042","country":"NL","loyaltyTier":"gold"},
                             "basket":[{"sku":"NEST-01","qty":1,"unitPrice":4999}],
                             "payment":{"method":"card","brand":"visa","outcome":"approved"},
                             "expected":{"status":"paid","emails":["order-confirmation"]}}"""),
                    example(json, mapper, "Checkout — expired card",
                            "The card is refused, so the order must stay unpaid",
                            List.of("checkout", "negative"),
                            """
                            {"customer":{"id":"cus_1042","country":"NL","loyaltyTier":"gold"},
                             "basket":[{"sku":"NEST-01","qty":1,"unitPrice":4999}],
                             "payment":{"method":"card","brand":"visa","outcome":"expired_card"},
                             "expected":{"status":"payment_failed","emails":[]}}"""),
                    example(json, mapper, "Bulk import — 10k rows",
                            "Inputs for the nightly importer under load",
                            List.of("import", "load"),
                            """
                            {"source":{"bucket":"acme-imports","key":"2026-09-01/orders.csv"},
                             "rows":10000,"batchSize":500,"stopOnError":false,
                             "expected":{"imported":9998,"rejected":2,"maxDurationSeconds":180}}"""));

            repository.saveAll(examples);
            log.info("Seeded {} example profiles into an empty database", examples.size());
        };
    }

    private static Profile example(ObjectMapper json, ProfileMapper mapper, String name,
                                                              String description, List<String> tags, String inputs)
            throws Exception {
        var parsed = json.readTree(inputs);
        return new Profile(name, description, tags, parsed, mapper.sizeOf(parsed));
    }
}
