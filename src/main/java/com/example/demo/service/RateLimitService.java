package com.example.demo.service;

import com.example.demo.dto.response.CheckResponse;
import com.example.demo.dto.response.UsageResponse;
import com.example.demo.entity.RateLimitConfig;
import com.example.demo.event.EventPublisher;
import com.example.demo.event.RateLimitEvent;
import com.example.demo.exception.ApiKeyRequiredException;
import com.example.demo.exception.RateLimitExceededException;
import com.example.demo.ratelimiter.IncrementResult;
import com.example.demo.ratelimiter.RateLimiter;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class RateLimitService {

    private final ConfigService configService;
    private final RateLimiter rateLimiter;
    private final EventPublisher eventPublisher;

    public RateLimitService(ConfigService configService, RateLimiter rateLimiter, EventPublisher eventPublisher) {
        this.configService = configService;
        this.rateLimiter = rateLimiter;
        this.eventPublisher = eventPublisher;
    }

    public CheckResponse check(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ApiKeyRequiredException();
        }

        Optional<RateLimitConfig> configOpt = configService.getConfig(apiKey);
        if (configOpt.isEmpty()) {
            return CheckResponse.noRuleConfigured();
        }

        RateLimitConfig config = configOpt.get();
        IncrementResult result = rateLimiter.increment(apiKey, config.getWindowSeconds());

        if (result.count() > config.getRequestLimit()) {
            eventPublisher.publish(RateLimitEvent.exceeded(
                apiKey, result.count(), config.getRequestLimit(), config.getWindowSeconds()));
            throw new RateLimitExceededException(result.count(), config.getRequestLimit(), result.ttlSeconds());
        }

        long remaining = config.getRequestLimit() - result.count();
        return CheckResponse.allowed(result.count(), config.getRequestLimit(), remaining);
    }

    public UsageResponse getUsage(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ApiKeyRequiredException();
        }

        Optional<RateLimitConfig> configOpt = configService.getConfig(apiKey);
        IncrementResult usage = rateLimiter.getCount(apiKey);

        if (configOpt.isEmpty()) {
            return UsageResponse.noUsage(apiKey);
        }

        RateLimitConfig config = configOpt.get();
        return UsageResponse.withUsage(apiKey, usage.count(), config.getRequestLimit(), usage.ttlSeconds());
    }
}
