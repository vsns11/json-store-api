package com.nest.jsonstore.error;

import java.util.UUID;

public class DocumentNotFoundException extends RuntimeException {

    public DocumentNotFoundException(UUID id) {
        super("No document found with id " + id);
    }
}
