package com.nest.jsonstore.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

@Configuration
@EnableConfigurationProperties({CorsProperties.class, LimitsProperties.class})
class WebConfig {

    /**
     * Adds ETags to GET responses, so repeat reads from a browser or CDN come back as a cheap 304.
     * CORS is configured with the security filter chain, which owns everything under /api.
     */
    @Bean
    ShallowEtagHeaderFilter etagFilter() {
        return new ShallowEtagHeaderFilter();
    }
}
