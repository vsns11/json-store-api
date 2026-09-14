package com.nest.jsonstore.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nest.jsonstore.error.InvalidInputsException;
import com.nest.jsonstore.profile.dto.ProfileRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;

/**
 * Puts a few example profiles in an empty database so a fresh environment has something to show.
 *
 * The examples live in {@code examples/profiles.json}: TMF702 resources and test scenarios, one set
 * for each catalogue that ships. Each is saved through the same service a user's save goes through,
 * so the server checks it against the catalogue being served, and only the ones written for that
 * catalogue are kept. ExampleProfilesTest proves every example fits exactly one shipped catalogue.
 *
 * Runs only under the {@code local} profile, and never touches a database that already has data.
 */
@Configuration
@org.springframework.context.annotation.Profile("local")
@ConditionalOnProperty(name = "app.seed-examples", havingValue = "true", matchIfMissing = true)
class ExampleDataSeeder {

    static final String EXAMPLES = "examples/profiles.json";

    private static final Logger log = LoggerFactory.getLogger(ExampleDataSeeder.class);

    @Bean
    ApplicationRunner seedExamples(ProfileRepository repository, ProfileService profiles, ObjectMapper json) {
        return args -> {
            if (repository.count() > 0) {
                return;
            }

            JsonNode examples;
            try (InputStream stream = new ClassPathResource(EXAMPLES).getInputStream()) {
                examples = json.readTree(stream);
            }

            int seeded = 0;
            int otherCatalogue = 0;
            for (JsonNode example : examples) {
                try {
                    profiles.create(json.treeToValue(example, ProfileRequest.class));
                    seeded++;
                } catch (InvalidInputsException mismatch) {
                    // Written for the other catalogue: expected, and not worth more than a debug line.
                    otherCatalogue++;
                    log.debug("Example '{}' is not for the catalogue in use: {}", example.path("name").asText(), mismatch.getMessage());
                }
            }
            log.info("Seeded {} example profiles for the catalogue in use; {} written for another catalogue were left out",
                    seeded, otherCatalogue);
        };
    }
}
