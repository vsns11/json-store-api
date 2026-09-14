package com.nest.jsonstore.error;

import java.io.IOException;

/**
 * Raised while a request body is being read, once more of it has arrived than the limit allows. An
 * IOException, because that is what a stream may throw; it reaches the error handler wrapped in the
 * failure to read the request.
 */
public class RequestBodyTooLargeException extends IOException {

    public RequestBodyTooLargeException(long limit) {
        super("The request body is over the %,d byte limit".formatted(limit));
    }
}
