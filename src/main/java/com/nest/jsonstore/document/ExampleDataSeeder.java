package com.nest.jsonstore.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.List;

/**
 * Puts a few example documents in an empty database so a fresh development environment has something
 * to show. Never runs under the {@code prod} profile, and never touches a database that already has data.
 */
@Configuration
@Profile("!prod")
@ConditionalOnProperty(name = "app.seed-examples", havingValue = "true", matchIfMissing = true)
class ExampleDataSeeder {

    private static final Logger log = LoggerFactory.getLogger(ExampleDataSeeder.class);

    @Bean
    ApplicationRunner seedExamples(JsonDocumentRepository repository, JsonDocumentMapper mapper, ObjectMapper json) {
        return args -> {
            if (repository.count() > 0) {
                return;
            }
            List<JsonDocument> examples = List.of(
                    example(json, mapper, "Feature flags", "Rollout switches for the web client",
                            List.of("config", "frontend"),
                            """
                            {"checkout.newFlow":true,"search.fuzzy":false,
                             "rollout":{"percentage":25,"regions":["eu-west","us-east"]}}"""),
                    example(json, mapper, "Order webhook sample", "Example payload from the payments provider",
                            List.of("webhook", "payments"),
                            """
                            {"event":"order.paid","id":"evt_8Xk2","amount":{"value":4999,"currency":"EUR"},
                             "items":[{"sku":"NEST-01","qty":2}]}"""),
                    example(json, mapper, "Service topology", "Which service talks to which",
                            List.of("infra"),
                            """
                            {"services":[{"name":"api","dependsOn":["db","cache"]},
                             {"name":"worker","dependsOn":["db","queue"]}]}"""));

            repository.saveAll(examples);
            log.info("Seeded {} example documents into an empty database", examples.size());
        };
    }

    private static JsonDocument example(ObjectMapper json, JsonDocumentMapper mapper, String name, String description,
                                        List<String> tags, String payload) throws Exception {
        var parsed = json.readTree(payload);
        return new JsonDocument(name, description, tags, parsed, mapper.sizeOf(parsed));
    }
}
