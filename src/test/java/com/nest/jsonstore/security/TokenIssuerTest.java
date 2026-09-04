package com.nest.jsonstore.security;

import com.nest.jsonstore.error.SessionExpiredException;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenIssuerTest {

    private static final SecretKeySpec KEY =
            new SecretKeySpec("a-test-secret-that-is-long-enough-for-hs256".getBytes(StandardCharsets.UTF_8), "HmacSHA256");

    private final JwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(KEY.getEncoded()));
    private final NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(KEY).macAlgorithm(MacAlgorithm.HS256).build();

    private TokenIssuer issuerWith(Duration ttl, Duration maxSession) {
        return new TokenIssuer(encoder, new SecurityProperties(
                new SecurityProperties.Jwt("unused", ttl, maxSession),
                new SecurityProperties.Ldap(null, null, null, null, null)));
    }

    private static UsernamePasswordAuthenticationToken signIn() {
        return new UsernamePasswordAuthenticationToken("alice", "secret",
                List.of(new SimpleGrantedAuthority("ROLE_ADMINS"), new SimpleGrantedAuthority("ROLE_DEVELOPERS")));
    }

    @Test
    void aFreshTokenRecordsWhenThePasswordWasTyped() {
        TokenIssuer issuer = issuerWith(Duration.ofHours(8), Duration.ofHours(24));
        Instant before = Instant.now().minusSeconds(1);

        Jwt token = decoder.decode(issuer.issue(signIn()).value());

        assertThat(token.getClaimAsString("iss")).isEqualTo(TokenIssuer.ISSUER);
        assertThat(token.getSubject()).isEqualTo("alice");
        assertThat(token.getClaimAsStringList("roles")).containsExactly("ADMINS", "DEVELOPERS");
        assertThat(token.getClaimAsInstant(TokenIssuer.AUTH_TIME)).isAfterOrEqualTo(before);
        assertThat(token.getExpiresAt()).isAfter(Instant.now().plus(Duration.ofHours(7)));
    }

    @Test
    void renewingKeepsTheOriginalSignInTimeAndRoles() {
        TokenIssuer issuer = issuerWith(Duration.ofHours(8), Duration.ofHours(24));
        Jwt first = decoder.decode(issuer.issue(signIn()).value());

        Jwt renewed = decoder.decode(issuer.renew(new JwtAuthenticationToken(first,
                List.of(new SimpleGrantedAuthority("ROLE_ADMINS")))).value());

        assertThat(renewed.getClaimAsInstant(TokenIssuer.AUTH_TIME)).isEqualTo(first.getClaimAsInstant(TokenIssuer.AUTH_TIME));
        assertThat(renewed.getClaimAsStringList("roles")).containsExactly("ADMINS");
    }

    @Test
    void refusesToRenewASignInOlderThanTheMaximumSession() {
        // A session that may not outlive its first token cannot be renewed at all.
        TokenIssuer issuer = issuerWith(Duration.ofHours(8), Duration.ZERO);
        Jwt token = decoder.decode(issuer.issue(signIn()).value());

        assertThatThrownBy(() -> issuer.renew(new JwtAuthenticationToken(token, List.of())))
                .isInstanceOf(SessionExpiredException.class);
    }
}
