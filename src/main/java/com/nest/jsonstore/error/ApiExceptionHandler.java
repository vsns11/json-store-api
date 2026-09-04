package com.nest.jsonstore.error;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Every failure leaves through here, in the one shape {@link ApiError} describes. Client mistakes
 * are answered with their own status and a message that says what to change; anything the server
 * did not expect is logged with its stack trace and answered with a 500 that gives nothing away.
 */
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

    /**
     * A body that could not be turned into a request. Syntax errors report exactly where Jackson gave
     * up; a body that parses but has the wrong shape — a string where a list belongs — names the field,
     * rather than leaking the Java type it failed to bind to.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException e) {
        // A syntax error inside a nested value arrives wrapped in a mapping exception, so the whole
        // chain is searched for one before the failure is taken to be about shape.
        for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof JsonParseException parse) {
                ApiError error = ApiError.of(400, "Invalid JSON", parse.getOriginalMessage());
                if (parse.getLocation() != null) {
                    error = error.withLocation(new ApiError.Location(parse.getLocation().getLineNr(), parse.getLocation().getColumnNr()));
                }
                return ResponseEntity.badRequest().body(error);
            }
        }

        if (e.getCause() instanceof JsonMappingException mapping) {
            String field = mapping.getPath().stream()
                    .map(ref -> ref.getFieldName() != null ? ref.getFieldName() : "[" + ref.getIndex() + "]")
                    .collect(Collectors.joining("."));
            String message = field.isEmpty()
                    ? "The request body has the wrong shape"
                    : "'" + field + "' has the wrong type";
            return ResponseEntity.badRequest().body(ApiError.of(400, "Invalid request", message));
        }

        return ResponseEntity.badRequest()
                .body(ApiError.of(400, "Invalid request", "The request body is missing or is not valid JSON"));
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

    @ExceptionHandler(SessionExpiredException.class)
    ResponseEntity<ApiError> handleSessionExpired(SessionExpiredException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiError.of(401, "Session expired", e.getMessage()));
    }

    /** Method security, should any be added, raises this from inside a controller. */
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiError.of(403, "Forbidden", "Your account is not allowed to do this"));
    }

    @ExceptionHandler(InvalidDocumentsException.class)
    ResponseEntity<ApiError> handleInvalidDocuments(InvalidDocumentsException e) {
        return ResponseEntity.badRequest().body(ApiError.of(400, "Invalid inputs", e.getMessage()));
    }

    @ExceptionHandler(InvalidTemplateException.class)
    ResponseEntity<ApiError> handleInvalidTemplate(InvalidTemplateException e) {
        return ResponseEntity.badRequest().body(ApiError.of(400, "Invalid template", e.getMessage()));
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
                .body(ApiError.of(404, "Not found", "No endpoint at /" + e.getResourcePath()));
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

    /**
     * Everything else. Spring's own request errors — an unsupported Content-Type, a missing query
     * parameter — already know their status and carry a message meant for the client, so those are
     * passed on as they are. Only a genuinely unexpected failure is a 500, and that one is logged in full.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(Exception e) {
        if (e instanceof ErrorResponse known && known.getStatusCode().is4xxClientError()) {
            HttpStatusCode status = known.getStatusCode();
            String reason = HttpStatus.resolve(status.value()) != null
                    ? HttpStatus.resolve(status.value()).getReasonPhrase()
                    : "Request failed";
            String detail = known.getBody().getDetail();
            return ResponseEntity.status(status)
                    .body(ApiError.of(status.value(), reason, detail != null ? detail : reason));
        }

        log.error("Unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of(500, "Server error", "Something went wrong while handling the request"));
    }
}
