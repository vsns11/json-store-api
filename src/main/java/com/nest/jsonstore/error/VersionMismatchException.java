package com.nest.jsonstore.error;

/** A change made to a version of a profile that is no longer the stored one. */
public class VersionMismatchException extends RuntimeException {

    public VersionMismatchException(String name, String changedBy) {
        super(changedBy == null
                ? "“" + name + "” was changed since you loaded it"
                : "“" + name + "” was changed by " + changedBy + " since you loaded it");
    }
}
