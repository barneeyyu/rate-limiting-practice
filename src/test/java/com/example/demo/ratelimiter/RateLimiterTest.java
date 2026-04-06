package com.example.demo.ratelimiter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RateLimiterTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private DefaultRedisScript<java.util.List> incrementScript;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private RateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        rateLimiter = new RateLimiter(redisTemplate, incrementScript);
    }

    @Test
    void increment_firstRequest_returnsCountOneWithTtl() {
        when(redisTemplate.execute(eq(incrementScript), anyList(), any()))
            .thenReturn(Arrays.asList(1L, 60L));

        IncrementResult result = rateLimiter.increment("test-key", 60);

        assertThat(result.count()).isEqualTo(1);
        assertThat(result.ttlSeconds()).isEqualTo(60);
    }

    @Test
    void increment_subsequentRequest_returnsIncrementedCount() {
        when(redisTemplate.execute(eq(incrementScript), anyList(), any()))
            .thenReturn(Arrays.asList(5L, 45L));

        IncrementResult result = rateLimiter.increment("test-key", 60);

        assertThat(result.count()).isEqualTo(5);
        assertThat(result.ttlSeconds()).isEqualTo(45);
    }

    @Test
    void increment_nullResult_returnsDefaults() {
        when(redisTemplate.execute(eq(incrementScript), anyList(), any()))
            .thenReturn(null);

        IncrementResult result = rateLimiter.increment("test-key", 60);

        assertThat(result.count()).isEqualTo(1);
        assertThat(result.ttlSeconds()).isEqualTo(60);
    }

    @Test
    void getCount_existingKey_returnsCountAndTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("ratelimit:count:test-key")).thenReturn("10");
        when(redisTemplate.getExpire("ratelimit:count:test-key", TimeUnit.SECONDS)).thenReturn(30L);

        IncrementResult result = rateLimiter.getCount("test-key");

        assertThat(result.count()).isEqualTo(10);
        assertThat(result.ttlSeconds()).isEqualTo(30);
    }

    @Test
    void getCount_missingKey_returnsZero() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("ratelimit:count:test-key")).thenReturn(null);

        IncrementResult result = rateLimiter.getCount("test-key");

        assertThat(result.count()).isEqualTo(0);
        assertThat(result.ttlSeconds()).isEqualTo(0);
    }

    @Test
    void delete_removesKey() {
        rateLimiter.delete("test-key");

        verify(redisTemplate).delete("ratelimit:count:test-key");
    }
}
