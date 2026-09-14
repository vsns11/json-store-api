package com.nest.jsonstore.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/** Signing and reading the bearer token that a successful LDAP bind hands out. */
@Configuration
class JwtConfig {

    /** The secret application.yml falls back to under the local profile, and nowhere else. */
    static final String DEVELOPMENT_SECRET = "development-only-secret-not-for-production-use";

    private final SecretKeySpec key;

    JwtConfig(SecurityProperties properties, Environment environment) {
        String configured = properties.jwt().secret();
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("app.security.jwt.secret is required: set JWT_SECRET");
        }
        // The development secret is in this repository, so anyone who has read it can sign a token for
        // any account with any role. It is refused everywhere except a developer's own machine — even
        // when set on purpose — so a staging deployment with a mistyped profile cannot run on it.
        if (DEVELOPMENT_SECRET.equals(configured) && !environment.acceptsProfiles(Profiles.of("local"))) {
            throw new IllegalStateException("app.security.jwt.secret is the development secret, which is published "
                    + "in this repository. Set JWT_SECRET to one of your own; the development secret is only "
                    + "accepted under the local profile.");
        }
        byte[] secret = configured.getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException("app.security.jwt.secret must be at least 32 characters");
        }
        this.key = new SecretKeySpec(secret, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key.getEncoded()));
    }

    /**
     * Besides the signature and the expiry, the issuer is checked: a token minted by another
     * service that happens to share the secret is not a token for this one.
     */
    @Bean
    JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(TokenIssuer.ISSUER));
        return decoder;
    }

    /** Turns the token's `roles` claim back into Spring Security authorities. */
    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }
}
