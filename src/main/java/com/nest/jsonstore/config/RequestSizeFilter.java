package com.nest.jsonstore.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nest.jsonstore.error.ApiError;
import com.nest.jsonstore.error.RequestBodyTooLargeException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Refuses a request body that is too big, without reading more of it than the limit.
 *
 * The payload limit in {@link LimitsProperties} is checked on the parsed, minified inputs, which
 * means the whole body has already been read into memory by then. A body that announces more than
 * the request limit is turned away here on its Content-Length alone. But a body sent in chunks
 * announces no length at all, and used to pass straight through, so every body is also counted as
 * it is read, and reading stops with an error the moment it goes over.
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
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long announced = request.getContentLengthLong();
        if (announced > limits.maxRequestBytes()) {
            response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            ApiError error = ApiError.of(HttpStatus.PAYLOAD_TOO_LARGE.value(), "Payload too large",
                    "The request body is %,d bytes, which is over the %,d byte limit"
                            .formatted(announced, limits.maxRequestBytes()));
            objectMapper.writeValue(response.getWriter(), error);
            return;
        }
        chain.doFilter(new CountedRequest(request, limits.maxRequestBytes()), response);
    }

    /** A request whose body stops being readable once more than {@code limit} bytes have come through. */
    static final class CountedRequest extends HttpServletRequestWrapper {

        private final long limit;
        private ServletInputStream stream;
        private BufferedReader reader;

        CountedRequest(HttpServletRequest request, long limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = new CountingStream(super.getInputStream(), limit);
            }
            return stream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            if (reader == null) {
                String encoding = getCharacterEncoding();
                Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
                reader = new BufferedReader(new InputStreamReader(getInputStream(), charset));
            }
            return reader;
        }
    }

    private static final class CountingStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long limit;
        private long count;

        CountingStream(ServletInputStream delegate, long limit) {
            this.delegate = delegate;
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int next = delegate.read();
            if (next != -1) {
                counted(1);
            }
            return next;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = delegate.read(buffer, offset, length);
            if (read > 0) {
                counted(read);
            }
            return read;
        }

        private void counted(int bytes) throws RequestBodyTooLargeException {
            count += bytes;
            if (count > limit) {
                throw new RequestBodyTooLargeException(limit);
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            delegate.setReadListener(listener);
        }
    }
}
