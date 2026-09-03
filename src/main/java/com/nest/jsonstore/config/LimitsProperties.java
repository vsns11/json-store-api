package com.nest.jsonstore.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Guard rails on request size, configured under {@code app.limits}. Validated, so a nonsensical
 * value stops the service at startup rather than at the first request that trips over it.
 *
 * @param maxPayloadBytes largest set of inputs accepted for a single profile
 * @param maxPageSize     largest page the list endpoint will return
 */
@Validated
@ConfigurationProperties(prefix = "app.limits")
public record LimitsProperties(
        @Positive(message = "app.limits.max-payload-bytes must be a positive number of bytes")
        int maxPayloadBytes,

        @Positive(message = "app.limits.max-page-size must be at least 1")
        int maxPageSize) {
}
