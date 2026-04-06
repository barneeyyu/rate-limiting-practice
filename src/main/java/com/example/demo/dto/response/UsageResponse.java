package com.example.demo.dto.response;

public record UsageResponse(
    String apiKey,
    long currentCount,
    Integer limit,
    Long remaining,
    long ttlSeconds
) {
    public static UsageResponse noUsage(String apiKey) {
        return new UsageResponse(apiKey, 0, null, null, 0);
    }

    public static UsageResponse withUsage(String apiKey, long count, int limit, long ttl) {
        return new UsageResponse(apiKey, count, limit, Math.max(0, limit - count), ttl);
    }
}
