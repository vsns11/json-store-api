package com.nest.jsonstore;

import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

/**
 * Everything applying the migrations needs, and nothing else: a data source and Flyway. Deliberately
 * not a component, so the application's own scan never picks it up.
 */
@ImportAutoConfiguration({DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class})
class MigrationOnly {
}
