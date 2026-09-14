package com.nest.jsonstore.error;

/** The password was right, but the account is in no directory group that may use JSON Store. */
public class SignInNotPermittedException extends RuntimeException {

    public SignInNotPermittedException() {
        super("Your account is not in a group that may use JSON Store — ask your administrator for access");
    }
}
