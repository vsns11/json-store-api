package com.nest.jsonstore.security;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * How users are authenticated, configured under {@code app.security}.
 *
 * @param jwt  signing secret and lifetimes of the token handed to the browser
 * @param ldap where users and their groups live in the directory
 */
@Validated
@ConfigurationProperties(prefix = "app.security")
public record SecurityProperties(@NotNull Jwt jwt, @NotNull Ldap ldap) {

    /**
     * @param secret     HMAC key the tokens are signed with; at least 32 characters
     * @param ttl        how long one token is valid
     * @param maxSession how long a sign-in may be kept alive by refreshing, counted from the bind
     */
    public record Jwt(String secret, @NotNull Duration ttl, @NotNull Duration maxSession) {
    }

    /**
     * @param userDnPatterns   pattern the username is bound with, e.g. {@code uid={0},ou=people}
     * @param userSearchBase   used instead of the pattern when the DN cannot be templated
     * @param userSearchFilter filter applied within {@code userSearchBase}
     * @param groupSearchBase  where group entries live; membership becomes a role
     * @param groupSearchFilter filter matching the groups a user belongs to
     */
    public record Ldap(
            String userDnPatterns,
            String userSearchBase,
            String userSearchFilter,
            String groupSearchBase,
            String groupSearchFilter) {
    }
}
