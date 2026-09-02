package com.nest.jsonstore.error;

import java.util.UUID;

public class ProfileNotFoundException extends RuntimeException {

    public ProfileNotFoundException(UUID id) {
        super("No profile found with id " + id);
    }
}
