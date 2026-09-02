package com.nest.jsonstore.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * How users are authenticated, configured under {@code app.security}.
 *
 * @param jwt  signing secret and lifetime of the token handed to the browser
 * @param ldap where users and their groups live in the directory
 */
@ConfigurationProperties(prefix = "app.security")
public record SecurityProperties(Jwt jwt, Ldap ldap) {

    public record Jwt(String secret, Duration ttl) {
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
