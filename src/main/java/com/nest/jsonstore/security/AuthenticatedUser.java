package com.nest.jsonstore.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.util.List;

/**
 * Who the caller is, in the terms the API talks about: the directory's username for them, and the
 * roles — VIEWER, EDITOR, ADMIN — their directory groups gave them at sign-in.
 *
 * Spring Security prefixes role authorities with {@code ROLE_}; that prefix is an internal
 * convention, so it is stripped in this one place rather than in every response.
 */
public record AuthenticatedUser(String username, List<String> roles) {

    public static AuthenticatedUser of(Authentication authentication) {
        List<String> roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .map(authority -> authority.startsWith("ROLE_") ? authority.substring("ROLE_".length()) : authority)
                .sorted()
                .toList();
        return new AuthenticatedUser(authentication.getName(), roles);
    }
}
