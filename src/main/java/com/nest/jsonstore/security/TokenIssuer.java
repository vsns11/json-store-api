package com.nest.jsonstore.security;

import com.nest.jsonstore.error.SessionExpiredException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** Turns a successful LDAP bind into a bearer token the browser can carry. */
@Component
public class TokenIssuer {

    /** Written into every token and checked on the way back in. */
    public static final String ISSUER = "json-store";

    /**
     * When the password was last presented. It survives every refresh unchanged, so a token can be
     * renewed for a while without the password, but not forever.
     */
    static final String AUTH_TIME = "auth_time";

    private final JwtEncoder encoder;
    private final SecurityProperties properties;

    TokenIssuer(JwtEncoder encoder, SecurityProperties properties) {
        this.encoder = encoder;
        this.properties = properties;
    }

    /** A token for a sign-in that just happened: the session starts now. */
    public IssuedToken issue(Authentication authentication) {
        return issue(AuthenticatedUser.of(authentication), Instant.now());
    }

    /**
     * A token to replace a valid one. The roles are copied from the token rather than read again
     * from the directory: a refresh is a convenience for a long day, not a re-check of membership,
     * which happens at the next real sign-in. That is also why a session cannot be refreshed past
     * {@code app.security.jwt.max-session}.
     */
    public IssuedToken renew(JwtAuthenticationToken current) {
        Jwt token = current.getToken();
        Instant authTime = token.getClaimAsInstant(AUTH_TIME);
        if (authTime == null) {
            authTime = token.getIssuedAt();
        }
        if (authTime == null || Instant.now().isAfter(authTime.plus(properties.jwt().maxSession()))) {
            throw new SessionExpiredException("This sign-in has reached its maximum length — sign in again");
        }
        return issue(AuthenticatedUser.of(current), authTime);
    }

    private IssuedToken issue(AuthenticatedUser user, Instant authTime) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.jwt().ttl());

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .issuedAt(now)
                .expiresAt(expiresAt)
                .subject(user.username())
                .claim("roles", user.roles())
                .claim(AUTH_TIME, authTime.getEpochSecond())
                .build();

        String value = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        return new IssuedToken(value, expiresAt, user);
    }

    /** A signed token and the identity inside it. */
    public record IssuedToken(String value, Instant expiresAt, AuthenticatedUser user) {
    }
}
