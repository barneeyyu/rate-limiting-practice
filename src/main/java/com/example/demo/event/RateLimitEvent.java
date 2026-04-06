package com.example.demo.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record RateLimitEvent(
    EventType eventType,
    String apiKey,
    String timestamp,
    Map<String, Object> details
) {
    public enum EventType {
        RATE_LIMIT_EXCEEDED,
        CONFIG_CREATED,
        CONFIG_DELETED
    }

    public static RateLimitEvent exceeded(String apiKey, long currentCount, int limit, int windowSeconds) {
        return new RateLimitEvent(
            EventType.RATE_LIMIT_EXCEEDED,
            apiKey,
            Instant.now().toString(),
            Map.of("currentCount", currentCount, "limit", limit, "windowSeconds", windowSeconds)
        );
    }

    public static RateLimitEvent configCreated(String apiKey, int limit, int windowSeconds) {
        return new RateLimitEvent(
            EventType.CONFIG_CREATED,
            apiKey,
            Instant.now().toString(),
            Map.of("limit", limit, "windowSeconds", windowSeconds)
        );
    }

    public static RateLimitEvent configDeleted(String apiKey) {
        return new RateLimitEvent(
            EventType.CONFIG_DELETED,
            apiKey,
            Instant.now().toString(),
            null
        );
    }
}
