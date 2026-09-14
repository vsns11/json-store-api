package com.nest.jsonstore.error;

/** Too many wrong passwords for one username in a short time. */
public class TooManySignInAttemptsException extends RuntimeException {

    private final long retryAfterSeconds;

    public TooManySignInAttemptsException(long retryAfterSeconds) {
        super("Too many wrong passwords for this username — wait " + retryAfterSeconds + " seconds and try again");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
