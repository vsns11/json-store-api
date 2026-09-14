package com.nest.jsonstore.profile.dto;

import java.util.UUID;

/**
 * What rebuilding one stored profile from its template found.
 *
 * @param outcome {@code unchanged}, {@code would-change} (a dry run), {@code repaired}, {@code invalid}
 *                (its template no longer fits the catalogue) or {@code not-templated}
 * @param detail  why, for {@code invalid} and {@code not-templated}; otherwise null
 */
public record RecomposeOutcome(UUID id, String name, String outcome, String detail) {
}
