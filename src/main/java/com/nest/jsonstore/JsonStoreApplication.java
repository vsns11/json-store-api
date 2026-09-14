package com.nest.jsonstore;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import java.util.Arrays;

@SpringBootApplication
@ConfigurationPropertiesScan
public class JsonStoreApplication {

    /** Applies the database migrations and exits, instead of starting the API. */
    static final String MIGRATE_ONLY = "--migrate-only";

    public static void main(String[] args) {
        if (Arrays.asList(args).contains(MIGRATE_ONLY)) {
            System.exit(migrate(args));
        }
        SpringApplication.run(JsonStoreApplication.class, args);
    }

    /**
     * Applies the migrations and stops: no web server, no directory, no tokens, so it needs only the
     * database settings. The Helm chart runs this as a Job before a release rolls out, which means a
     * migration that fails stops the release rather than crash-looping new pods, and serving pods never
     * change the schema themselves.
     *
     * @return the process exit code: zero once every migration is applied
     */
    static int migrate(String... args) {
        SpringApplication application = new SpringApplication(MigrationOnly.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        return SpringApplication.exit(application.run(args));
    }
}
