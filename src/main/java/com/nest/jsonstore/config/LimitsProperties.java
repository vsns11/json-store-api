package com.nest.jsonstore.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Guard rails on request size, configured via {@code app.limits}.
 *
 * @param maxPayloadBytes largest JSON payload accepted for a single document
 * @param maxPageSize     largest page the list endpoint will return
 */
@ConfigurationProperties(prefix = "app.limits")
public record LimitsProperties(int maxPayloadBytes, int maxPageSize) {
}
