package com.nest.jsonstore.error;

public class PayloadTooLargeException extends RuntimeException {

    public PayloadTooLargeException(int actualBytes, int limitBytes) {
        super("The JSON payload is %,d bytes, which is over the %,d byte limit".formatted(actualBytes, limitBytes));
    }
}
