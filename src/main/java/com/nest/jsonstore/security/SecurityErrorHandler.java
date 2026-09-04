package com.nest.jsonstore.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nest.jsonstore.error.ApiError;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * What a caller gets when the token is missing, expired, or not enough.
 *
 * Spring Security answers both cases with an empty body by default, which leaves a client with
 * nothing but a status code to show. These delegate to the standard bearer-token handlers — so the
 * {@code WWW-Authenticate} header is exactly what RFC 6750 asks for — and then write the same
 * error shape every other endpoint uses.
 */
@Component
class SecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final AuthenticationEntryPoint bearerEntryPoint = new BearerTokenAuthenticationEntryPoint();
    private final AccessDeniedHandler bearerDeniedHandler = new BearerTokenAccessDeniedHandler();
    private final ObjectMapper objectMapper;

    SecurityErrorHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** No token, or one the API will not accept. */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException failure)
            throws IOException, ServletException {
        bearerEntryPoint.commence(request, response, failure);
        write(response, "Unauthorized", "Sign in and send the bearer token with the request");
    }

    /** A valid token, but the account is not in a group that may do this. */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException failure)
            throws IOException, ServletException {
        bearerDeniedHandler.handle(request, response, failure);
        write(response, "Forbidden", "Your account is not allowed to do this");
    }

    private void write(HttpServletResponse response, String error, String message) throws IOException {
        if (response.isCommitted()) return;
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), ApiError.of(response.getStatus(), error, message));
    }
}
