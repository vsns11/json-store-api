package com.nest.jsonstore.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * Origins allowed to call the API, configured via {@code app.cors.allowed-origins}. Empty is
 * valid: it means the browser app is served from the same origin and no cross-origin call is made.
 */
@Validated
@ConfigurationProperties(prefix = "app.cors")
public record CorsProperties(@NotNull List<String> allowedOrigins) {
}
