package com.example.demo.exception;

public class ApiKeyRequiredException extends RuntimeException {
    public ApiKeyRequiredException() {
        super("API key is required");
    }
}
