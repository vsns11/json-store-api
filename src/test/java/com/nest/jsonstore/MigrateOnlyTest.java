package com.nest.jsonstore;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;

/** The runner the migration Job uses: every migration applied, then a clean exit. Requires Docker. */
@Testcontainers
class MigrateOnlyTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void appliesEveryMigrationAndExits() throws Exception {
        int exit = JsonStoreApplication.migrate(
                JsonStoreApplication.MIGRATE_ONLY,
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword());

        assertThat(exit).isZero();
        try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             ResultSet applied = connection.createStatement().executeQuery(
                     "select count(*) from flyway_schema_history where success and version is not null")) {
            applied.next();
            assertThat(applied.getInt(1)).isGreaterThanOrEqualTo(8);
        }
    }
}
