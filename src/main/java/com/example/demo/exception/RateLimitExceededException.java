package com.example.demo.exception;

public class RateLimitExceededException extends RuntimeException {
    private final long currentCount;
    private final int limit;
    private final long retryAfterSeconds;

    public RateLimitExceededException(long currentCount, int limit, long retryAfterSeconds) {
        super("Rate limit exceeded");
        this.currentCount = currentCount;
        this.limit = limit;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getCurrentCount() { return currentCount; }
    public int getLimit() { return limit; }
    public long getRetryAfterSeconds() { return retryAfterSeconds; }
}
