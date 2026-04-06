package com.example.demo.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.example.demo.entity.RateLimitConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Optional;

@Configuration
public class CacheConfig {

    @Value("${rate-limit.cache.caffeine-max-size:10000}")
    private int maxSize;

    @Value("${rate-limit.cache.caffeine-ttl-seconds:60}")
    private int ttlSeconds;

    @Bean
    public Cache<String, Optional<RateLimitConfig>> configCache() {
        return Caffeine.newBuilder()
            .maximumSize(maxSize)
            .expireAfterWrite(Duration.ofSeconds(ttlSeconds))
            .build();
    }
}
