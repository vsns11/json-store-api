package com.nest.jsonstore.error;

/**
 * A change sent without saying which version it was made to. Without that, two people editing the
 * same profile would silently overwrite each other; the last save would simply win.
 */
public class PreconditionRequiredException extends RuntimeException {

    public PreconditionRequiredException() {
        super("Send If-Match with the ETag of the version you loaded, or If-Match: * to overwrite whatever is stored");
    }
}
