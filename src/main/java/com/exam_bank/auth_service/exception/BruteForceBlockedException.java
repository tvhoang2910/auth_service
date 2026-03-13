package com.exam_bank.auth_service.exception;

public class BruteForceBlockedException extends RuntimeException {

    public BruteForceBlockedException(String message) {
        super(message);
    }
}