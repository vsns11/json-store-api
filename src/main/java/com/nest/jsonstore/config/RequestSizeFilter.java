package com.nest.jsonstore.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nest.jsonstore.error.ApiError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Refuses a request body that is obviously too big before anything reads it.
 *
 * The payload limit in {@link LimitsProperties} is checked on the parsed, minified inputs, which
 * means the whole body has already been read into memory by then. A client that announces a body of
 * hundreds of megabytes is turned away here, on the Content-Length alone, so it never gets that far.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class RequestSizeFilter extends OncePerRequestFilter {

    private final LimitsProperties limits;
    private final ObjectMapper objectMapper;

    RequestSizeFilter(LimitsProperties limits, ObjectMapper objectMapper) {
        this.limits = limits;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getContentLengthLong() <= limits.maxRequestBytes();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ApiError error = ApiError.of(HttpStatus.PAYLOAD_TOO_LARGE.value(), "Payload too large",
                "The request body is %,d bytes, which is over the %,d byte limit"
                        .formatted(request.getContentLengthLong(), limits.maxRequestBytes()));
        objectMapper.writeValue(response.getWriter(), error);
    }
}
