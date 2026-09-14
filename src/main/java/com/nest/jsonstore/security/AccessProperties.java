package com.nest.jsonstore.security;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Who may use JSON Store, and how hard sign-in is to guess at, configured under
 * {@code app.security.access}.
 *
 * Being able to bind to the directory is not enough: a company directory holds everyone. Only the
 * groups named here grant anything, so an account in none of them is refused at sign-in.
 *
 * @param viewerGroups       directory groups (their cn, any case) whose members may read
 * @param editorGroups       groups whose members may also create and change profiles
 * @param adminGroups        groups whose members may also delete profiles and run maintenance
 * @param usernameAttribute  the directory attribute holding the canonical username, recorded as the
 *                           author of a change whatever case was typed at sign-in
 * @param maxFailedSignIns   wrong passwords one username may try within the window, on one replica,
 *                           before sign-in pauses for that name
 * @param failedSignInWindow how far back those wrong passwords count
 */
@Validated
@ConfigurationProperties(prefix = "app.security.access")
public record AccessProperties(
        List<String> viewerGroups,
        List<String> editorGroups,
        List<String> adminGroups,
        @NotBlank String usernameAttribute,
        @Min(1) int maxFailedSignIns,
        @NotNull Duration failedSignInWindow) {

    public AccessProperties {
        viewerGroups = normalise(viewerGroups);
        editorGroups = normalise(editorGroups);
        adminGroups = normalise(adminGroups);
    }

    /** Group names as the directory's authorities carry them: trimmed and upper-cased. */
    private static List<String> normalise(List<String> groups) {
        return groups == null ? List.of() : groups.stream()
                .filter(StringUtils::hasText)
                .map(group -> group.trim().toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
    }
}
