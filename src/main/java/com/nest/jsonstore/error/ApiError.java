package com.nest.jsonstore.error;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * The single error shape the API returns, so the UI never has to guess.
 * {@code location} is filled in for JSON syntax errors and points at the offending line/column.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String message,
        List<FieldIssue> fieldErrors,
        Location location
) {

    public record FieldIssue(String field, String message) {
    }

    public record Location(int line, int column) {
    }

    public static ApiError of(int status, String error, String message) {
        return new ApiError(Instant.now(), status, error, message, null, null);
    }

    public ApiError withFieldErrors(List<FieldIssue> fieldErrors) {
        return new ApiError(timestamp(), status(), error(), message(), fieldErrors, location());
    }

    public ApiError withLocation(Location location) {
        return new ApiError(timestamp(), status(), error(), message(), fieldErrors(), location);
    }
}
