package com.nest.jsonstore.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.ShallowEtagHeaderFilter;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties({CorsProperties.class, LimitsProperties.class})
class WebConfig implements WebMvcConfigurer {

    private final CorsProperties corsProperties;

    WebConfig(CorsProperties corsProperties) {
        this.corsProperties = corsProperties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(corsProperties.allowedOrigins().toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders(RequestIdFilter.HEADER)
                .maxAge(Duration.ofHours(1).toSeconds());
    }

    /**
     * Adds ETags to GET responses, so repeat reads from a browser or CDN come back as a cheap 304.
     */
    @Bean
    ShallowEtagHeaderFilter etagFilter() {
        return new ShallowEtagHeaderFilter();
    }
}
