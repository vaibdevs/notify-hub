package com.notifyhub.exception;

public class DeliveryFailedException extends RuntimeException {

    public DeliveryFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
