package com.example.demo.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CheckResponse(
    boolean allowed,
    Long currentCount,
    Integer limit,
    Long remaining,
    Long retryAfterSeconds,
    String message
) {
    public static CheckResponse allowed(long currentCount, int limit, long remaining) {
        return new CheckResponse(true, currentCount, limit, remaining, null, null);
    }

    public static CheckResponse blocked(long currentCount, int limit, long retryAfterSeconds) {
        return new CheckResponse(false, currentCount, limit, 0L, retryAfterSeconds, null);
    }

    public static CheckResponse noRuleConfigured() {
        return new CheckResponse(true, null, null, null, null, "No rate limit configured");
    }
}
