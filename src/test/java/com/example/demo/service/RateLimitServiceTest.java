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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RateLimitServiceTest {

    @Mock
    private ConfigService configService;

    @Mock
    private RateLimiter rateLimiter;

    @Mock
    private EventPublisher eventPublisher;

    private RateLimitService rateLimitService;

    @BeforeEach
    void setUp() {
        rateLimitService = new RateLimitService(configService, rateLimiter, eventPublisher);
    }

    @Test
    void check_nullApiKey_throwsApiKeyRequired() {
        assertThatThrownBy(() -> rateLimitService.check(null))
            .isInstanceOf(ApiKeyRequiredException.class);
    }

    @Test
    void check_blankApiKey_throwsApiKeyRequired() {
        assertThatThrownBy(() -> rateLimitService.check("  "))
            .isInstanceOf(ApiKeyRequiredException.class);
    }

    @Test
    void check_noRuleConfigured_returnsAllowedWithMessage() {
        when(configService.getConfig("test-key")).thenReturn(Optional.empty());

        CheckResponse response = rateLimitService.check("test-key");

        assertThat(response.allowed()).isTrue();
        assertThat(response.message()).isEqualTo("No rate limit configured");
        verifyNoInteractions(rateLimiter);
    }

    @Test
    void check_underLimit_returnsAllowed() {
        RateLimitConfig config = new RateLimitConfig("test-key", 100, 60);
        when(configService.getConfig("test-key")).thenReturn(Optional.of(config));
        when(rateLimiter.increment("test-key", 60)).thenReturn(new IncrementResult(5, 55));

        CheckResponse response = rateLimitService.check("test-key");

        assertThat(response.allowed()).isTrue();
        assertThat(response.currentCount()).isEqualTo(5);
        assertThat(response.limit()).isEqualTo(100);
        assertThat(response.remaining()).isEqualTo(95);
    }

    @Test
    void check_exactlyAtLimit_returnsAllowed() {
        RateLimitConfig config = new RateLimitConfig("test-key", 100, 60);
        when(configService.getConfig("test-key")).thenReturn(Optional.of(config));
        when(rateLimiter.increment("test-key", 60)).thenReturn(new IncrementResult(100, 30));

        CheckResponse response = rateLimitService.check("test-key");

        assertThat(response.allowed()).isTrue();
        assertThat(response.remaining()).isEqualTo(0);
    }

    @Test
    void check_overLimit_throwsRateLimitExceeded() {
        RateLimitConfig config = new RateLimitConfig("test-key", 100, 60);
        when(configService.getConfig("test-key")).thenReturn(Optional.of(config));
        when(rateLimiter.increment("test-key", 60)).thenReturn(new IncrementResult(101, 45));

        assertThatThrownBy(() -> rateLimitService.check("test-key"))
            .isInstanceOf(RateLimitExceededException.class)
            .satisfies(ex -> {
                RateLimitExceededException e = (RateLimitExceededException) ex;
                assertThat(e.getCurrentCount()).isEqualTo(101);
                assertThat(e.getLimit()).isEqualTo(100);
                assertThat(e.getRetryAfterSeconds()).isEqualTo(45);
            });
    }

    @Test
    void check_overLimit_publishesEventBeforeException() {
        RateLimitConfig config = new RateLimitConfig("test-key", 100, 60);
        when(configService.getConfig("test-key")).thenReturn(Optional.of(config));
        when(rateLimiter.increment("test-key", 60)).thenReturn(new IncrementResult(101, 45));

        try {
            rateLimitService.check("test-key");
        } catch (RateLimitExceededException ignored) {
        }

        verify(eventPublisher).publish(any(RateLimitEvent.class));
    }

    @Test
    void getUsage_noConfig_returnsNoUsage() {
        when(configService.getConfig("test-key")).thenReturn(Optional.empty());
        when(rateLimiter.getCount("test-key")).thenReturn(new IncrementResult(0, 0));

        UsageResponse response = rateLimitService.getUsage("test-key");

        assertThat(response.apiKey()).isEqualTo("test-key");
        assertThat(response.currentCount()).isEqualTo(0);
    }

    @Test
    void getUsage_withConfig_returnsUsage() {
        RateLimitConfig config = new RateLimitConfig("test-key", 100, 60);
        when(configService.getConfig("test-key")).thenReturn(Optional.of(config));
        when(rateLimiter.getCount("test-key")).thenReturn(new IncrementResult(50, 30));

        UsageResponse response = rateLimitService.getUsage("test-key");

        assertThat(response.apiKey()).isEqualTo("test-key");
        assertThat(response.currentCount()).isEqualTo(50);
        assertThat(response.limit()).isEqualTo(100);
        assertThat(response.remaining()).isEqualTo(50);
        assertThat(response.ttlSeconds()).isEqualTo(30);
    }
}
