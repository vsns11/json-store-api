package com.nest.jsonstore.error;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

@RestControllerAdvice
class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ProfileNotFoundException.class)
    ResponseEntity<ApiError> handleNotFound(ProfileNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of(404, "Not found", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException e) {
        List<ApiError.FieldIssue> issues = e.getBindingResult().getFieldErrors().stream()
                .map(error -> new ApiError.FieldIssue(error.getField(), error.getDefaultMessage()))
                .toList();
        String message = issues.isEmpty() ? "The request is not valid" : issues.getFirst().message();
        return ResponseEntity.badRequest()
                .body(ApiError.of(400, "Validation failed", message).withFieldErrors(issues));
    }

    /** Malformed JSON in the request body — report exactly where Jackson gave up. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException e) {
        ApiError error = ApiError.of(400, "Invalid JSON", "The request body is not valid JSON");
        if (e.getCause() instanceof JsonProcessingException cause && cause.getLocation() != null) {
            error = ApiError.of(400, "Invalid JSON", cause.getOriginalMessage())
                    .withLocation(new ApiError.Location(cause.getLocation().getLineNr(), cause.getLocation().getColumnNr()));
        }
        return ResponseEntity.badRequest().body(error);
    }

    /**
     * A failed sign-in. The message never says which half was wrong, so the endpoint cannot be
     * used to find out which usernames exist.
     */
    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ApiError> handleAuthenticationFailure(AuthenticationException e) {
        log.info("Sign-in refused: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiError.of(401, "Authentication failed", "Wrong username or password"));
    }

    @ExceptionHandler(PayloadTooLargeException.class)
    ResponseEntity<ApiError> handleTooLarge(PayloadTooLargeException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiError.of(413, "Payload too large", e.getMessage()));
    }

    /** A path variable that is not a UUID is a client mistake, not a server fault. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.badRequest()
                .body(ApiError.of(400, "Bad request", "'%s' is not a valid value for %s".formatted(e.getValue(), e.getName())));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> handleNoResource(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of(404, "Not found", "No endpoint at " + e.getResourcePath()));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiError.of(405, "Method not allowed", e.getMethod() + " is not supported on this endpoint"));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<ApiError> handleConflict(OptimisticLockingFailureException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of(409, "Conflict", "This profile was changed elsewhere — reload it and try again"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(Exception e) {
        log.error("Unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of(500, "Server error", "Something went wrong while handling the request"));
    }
}
