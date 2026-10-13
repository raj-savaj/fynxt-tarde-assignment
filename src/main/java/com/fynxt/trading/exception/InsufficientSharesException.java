package com.fynxt.trading.exception;

public class InsufficientSharesException extends RuntimeException {
    public InsufficientSharesException(String message) {
        super(message);
    }
}
