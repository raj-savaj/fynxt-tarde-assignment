package com.fynxt.trading.exception;

public class OrderLimitExceededException extends RuntimeException {
    public OrderLimitExceededException(String message) {
        super(message);
    }
}
