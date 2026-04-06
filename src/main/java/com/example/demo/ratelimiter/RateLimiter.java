package com.example.demo.ratelimiter;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class RateLimiter {

    private static final String KEY_PREFIX = "ratelimit:count:";

    private final RedisTemplate<String, String> redisTemplate;
    private final DefaultRedisScript<List> incrementScript;

    public RateLimiter(RedisTemplate<String, String> redisTemplate, DefaultRedisScript<List> incrementScript) {
        this.redisTemplate = redisTemplate;
        this.incrementScript = incrementScript;
    }

    public IncrementResult increment(String apiKey, int windowSeconds) {
        String key = KEY_PREFIX + apiKey;
        List<Long> result = redisTemplate.execute(
            incrementScript,
            Collections.singletonList(key),
            String.valueOf(windowSeconds)
        );

        if (result == null || result.size() < 2) {
            return new IncrementResult(1, windowSeconds);
        }

        return new IncrementResult(result.get(0), result.get(1));
    }

    public IncrementResult getCount(String apiKey) {
        String key = KEY_PREFIX + apiKey;
        String value = redisTemplate.opsForValue().get(key);

        if (value == null) {
            return new IncrementResult(0, 0);
        }

        Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        return new IncrementResult(Long.parseLong(value), ttl != null && ttl > 0 ? ttl : 0);
    }

    public void delete(String apiKey) {
        String key = KEY_PREFIX + apiKey;
        redisTemplate.delete(key);
    }
}
