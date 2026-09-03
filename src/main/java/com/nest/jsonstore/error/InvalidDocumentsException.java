package com.nest.jsonstore.error;

/** The inputs are not a usable set of named documents. */
public class InvalidDocumentsException extends RuntimeException {

    public InvalidDocumentsException(String message) {
        super(message);
    }
}
