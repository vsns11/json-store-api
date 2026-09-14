package com.nest.jsonstore.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Turns the groups someone belongs to in the directory into what they may do here. The roles nest:
 * an admin is also an editor, and an editor also a viewer, so each rule in the filter chain names
 * the least role it needs.
 */
@Component
class DirectoryRoles {

    static final String VIEWER = "VIEWER";
    static final String EDITOR = "EDITOR";
    static final String ADMIN = "ADMIN";

    private final AccessProperties access;

    DirectoryRoles(AccessProperties access) {
        if (access.viewerGroups().isEmpty() && access.editorGroups().isEmpty() && access.adminGroups().isEmpty()) {
            throw new IllegalStateException("No directory group is mapped to a role, so nobody could sign in. "
                    + "Set ROLE_VIEWER_GROUPS, ROLE_EDITOR_GROUPS or ROLE_ADMIN_GROUPS to the groups that may use JSON Store.");
        }
        this.access = access;
    }

    /** The roles for a directory sign-in, most powerful first; empty when none of the groups count. */
    List<String> rolesFor(Authentication authentication) {
        Set<String> groups = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .map(authority -> authority.startsWith("ROLE_") ? authority.substring("ROLE_".length()) : authority)
                .collect(Collectors.toSet());

        boolean admin = access.adminGroups().stream().anyMatch(groups::contains);
        boolean editor = admin || access.editorGroups().stream().anyMatch(groups::contains);
        boolean viewer = editor || access.viewerGroups().stream().anyMatch(groups::contains);

        List<String> roles = new ArrayList<>();
        if (admin) roles.add(ADMIN);
        if (editor) roles.add(EDITOR);
        if (viewer) roles.add(VIEWER);
        return roles;
    }
}
