package com.nest.jsonstore.error;

import java.util.List;

/**
 * Inputs the catalogue would not have produced: a template it does not offer, a required group left
 * unset, or a value of the wrong kind. Carries every problem found, each pointing at the part of the
 * request it is about, so the form can mark all of them at once rather than one per save.
 */
public class InvalidInputsException extends RuntimeException {

    private final List<ApiError.FieldIssue> issues;

    public InvalidInputsException(List<ApiError.FieldIssue> issues) {
        super(issues.isEmpty() ? "The inputs are not valid" : issues.getFirst().message());
        this.issues = List.copyOf(issues);
    }

    public InvalidInputsException(String field, String message) {
        this(List.of(new ApiError.FieldIssue(field, message)));
    }

    public List<ApiError.FieldIssue> issues() {
        return issues;
    }
}
