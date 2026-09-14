package com.nest.jsonstore.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({CorsProperties.class, LimitsProperties.class})
/**
 * CORS is configured with the security filter chain, which owns everything under /api.
 *
 * There is deliberately no ShallowEtagHeaderFilter: it buffered every response in memory to hash it,
 * and its ETags could not be sent back in If-Match. A profile's ETag is its version instead.
 */
class WebConfig {
}
