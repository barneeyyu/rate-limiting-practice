package com.example.demo.dto.response;

public record LimitResponse(
    String apiKey,
    int limit,
    int windowSeconds
) {}
