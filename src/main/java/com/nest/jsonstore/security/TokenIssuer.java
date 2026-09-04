package com.nest.jsonstore.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** Turns a successful LDAP bind into a bearer token the browser can carry. */
@Component
public class TokenIssuer {

    private final JwtEncoder encoder;
    private final SecurityProperties properties;

    TokenIssuer(JwtEncoder encoder, SecurityProperties properties) {
        this.encoder = encoder;
        this.properties = properties;
    }

    public IssuedToken issue(Authentication authentication) {
        AuthenticatedUser user = AuthenticatedUser.of(authentication);
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.jwt().ttl());

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("json-store")
                .issuedAt(now)
                .expiresAt(expiresAt)
                .subject(user.username())
                .claim("roles", user.roles())
                .build();

        String value = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        return new IssuedToken(value, expiresAt, user);
    }

    /** A signed token and the identity inside it. */
    public record IssuedToken(String value, Instant expiresAt, AuthenticatedUser user) {
    }
}
