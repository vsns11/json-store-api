package com.nest.jsonstore.error;

/** The token is still valid, but it has been renewed for as long as a single sign-in is allowed to last. */
public class SessionExpiredException extends RuntimeException {

    public SessionExpiredException(String message) {
        super(message);
    }
}
