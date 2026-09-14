package com.nest.jsonstore.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nest.jsonstore.error.RequestBodyTooLargeException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RequestSizeFilterTest {

    // The error body carries a timestamp, so the mapper needs the java.time support Spring's own has.
    private final RequestSizeFilter filter =
            new RequestSizeFilter(new LimitsProperties(10, 20, 100), new ObjectMapper().findAndRegisterModules());

    @Test
    void refusesABodyThatAnnouncesMoreThanTheLimit() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/profiles");
        request.setContent(new byte[21]);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("20 byte limit");
    }

    /** Sent in chunks, a body announces no length; it is stopped as it is read instead. */
    @Test
    void stopsReadingABodyThatAnnouncesNoLengthOnceItPassesTheLimit() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/profiles") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }

            @Override
            public int getContentLength() {
                return -1;
            }
        };
        request.setContent("x".repeat(50).getBytes(StandardCharsets.UTF_8));
        AtomicReference<IOException> thrown = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            try {
                req.getReader().lines().count();
            } catch (java.io.UncheckedIOException e) {
                thrown.set(e.getCause());
            }
        });

        assertThat(thrown.get()).isInstanceOf(RequestBodyTooLargeException.class);
    }

    @Test
    void passesABodyWithinTheLimitThroughUnchanged() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/profiles");
        request.setContent("{\"a\":1}".getBytes(StandardCharsets.UTF_8));
        AtomicReference<String> body = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> body.set(new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8)));

        assertThat(body.get()).isEqualTo("{\"a\":1}");
    }
}
