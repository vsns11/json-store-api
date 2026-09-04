package com.nest.jsonstore.error;

/** The template a profile claims to be composed from is not a selection plus values. */
public class InvalidTemplateException extends RuntimeException {

    public InvalidTemplateException(String message) {
        super(message);
    }
}
