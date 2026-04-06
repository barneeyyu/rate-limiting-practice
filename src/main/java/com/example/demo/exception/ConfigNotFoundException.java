package com.example.demo.exception;

public class ConfigNotFoundException extends RuntimeException {
    public ConfigNotFoundException(String apiKey) {
        super("Rate limit config not found for apiKey: " + apiKey);
    }
}
