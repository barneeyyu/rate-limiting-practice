package com.example.demo.service;

import com.example.demo.entity.RateLimitConfig;
import com.example.demo.event.EventPublisher;
import com.example.demo.event.RateLimitEvent;
import com.example.demo.exception.ConfigNotFoundException;
import com.example.demo.ratelimiter.RateLimiter;
import com.example.demo.repository.RateLimitConfigRepository;
import com.github.benmanes.caffeine.cache.Cache;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Service
public class ConfigService {

    private static final String REDIS_CONFIG_PREFIX = "ratelimit:config:";

    private final RateLimitConfigRepository repository;
    private final Cache<String, Optional<RateLimitConfig>> configCache;
    private final RedisTemplate<String, String> redisTemplate;
    private final RateLimiter rateLimiter;
    private final EventPublisher eventPublisher;
    private final Duration redisConfigTtl;

    public ConfigService(
            RateLimitConfigRepository repository,
            Cache<String, Optional<RateLimitConfig>> configCache,
            RedisTemplate<String, String> redisTemplate,
            RateLimiter rateLimiter,
            EventPublisher eventPublisher,
            @Value("${rate-limit.cache.redis-config-ttl-seconds:300}") int redisConfigTtlSeconds) {
        this.repository = repository;
        this.configCache = configCache;
        this.redisTemplate = redisTemplate;
        this.rateLimiter = rateLimiter;
        this.eventPublisher = eventPublisher;
        this.redisConfigTtl = Duration.ofSeconds(redisConfigTtlSeconds);
    }

    public Optional<RateLimitConfig> getConfig(String apiKey) {
        return configCache.get(apiKey, key -> {
            // Try Redis
            Map<Object, Object> redisData = redisTemplate.opsForHash().entries(REDIS_CONFIG_PREFIX + key);
            if (!redisData.isEmpty()) {
                return Optional.of(fromRedisHash(key, redisData));
            }

            // Try MySQL
            Optional<RateLimitConfig> dbConfig = repository.findByApiKey(key);
            if (dbConfig.isPresent()) {
                saveToRedis(dbConfig.get());
            }
            return dbConfig;
        });
    }

    @Transactional
    public RateLimitConfig createOrUpdate(String apiKey, int limit, int windowSeconds) {
        RateLimitConfig config = repository.findByApiKey(apiKey)
            .map(existing -> {
                existing.setRequestLimit(limit);
                existing.setWindowSeconds(windowSeconds);
                return existing;
            })
            .orElseGet(() -> new RateLimitConfig(apiKey, limit, windowSeconds));

        RateLimitConfig saved = repository.save(config);
        saveToRedis(saved);
        configCache.invalidate(apiKey);
        eventPublisher.publish(RateLimitEvent.configCreated(apiKey, limit, windowSeconds));
        return saved;
    }

    @Transactional
    public void delete(String apiKey) {
        if (!repository.existsByApiKey(apiKey)) {
            throw new ConfigNotFoundException(apiKey);
        }

        repository.deleteByApiKey(apiKey);
        redisTemplate.delete(REDIS_CONFIG_PREFIX + apiKey);
        rateLimiter.delete(apiKey);
        configCache.invalidate(apiKey);
        eventPublisher.publish(RateLimitEvent.configDeleted(apiKey));
    }

    public Page<RateLimitConfig> findAll(Pageable pageable) {
        return repository.findAll(pageable);
    }

    private void saveToRedis(RateLimitConfig config) {
        String key = REDIS_CONFIG_PREFIX + config.getApiKey();
        Map<String, String> hash = new HashMap<>();
        hash.put("limit", String.valueOf(config.getRequestLimit()));
        hash.put("windowSeconds", String.valueOf(config.getWindowSeconds()));
        redisTemplate.opsForHash().putAll(key, hash);
        redisTemplate.expire(key, redisConfigTtl);
    }

    private RateLimitConfig fromRedisHash(String apiKey, Map<Object, Object> hash) {
        int limit = Integer.parseInt((String) hash.get("limit"));
        int windowSeconds = Integer.parseInt((String) hash.get("windowSeconds"));
        return new RateLimitConfig(apiKey, limit, windowSeconds);
    }
}
