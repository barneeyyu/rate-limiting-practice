# Rate Limiter Specification

## Overview

The RateLimiter component encapsulates all Redis counter operations for tracking API request counts within time windows.

---

## Core Operation: increment(apiKey, windowSeconds)

### Purpose

Atomically increment the request counter and get current count + TTL.

### Redis Lua Script

```lua
-- Keys: KEYS[1] = ratelimit:count:{apiKey}
-- Args: ARGV[1] = windowSeconds

local count = redis.call('INCR', KEYS[1])

if count == 1 then
    redis.call('EXPIRE', KEYS[1], ARGV[1])
end

local ttl = redis.call('TTL', KEYS[1])

-- Handle edge cases
if ttl < 0 then
    ttl = tonumber(ARGV[1])
end

return {count, ttl}
```

### Why Lua Script?

| Approach | Problem |
|----------|---------|
| Separate INCR + EXPIRE | Race condition: key expires between commands |
| MULTI/EXEC | Still not atomic for conditional EXPIRE |
| Lua script | Fully atomic, single round trip |

### Return Value

```java
public record IncrementResult(long count, long ttlSeconds) {}
```

### TTL Edge Cases

| TTL Value | Meaning | Handling |
|-----------|---------|----------|
| > 0 | Normal | Use as-is |
| -1 | Key exists, no expiry set | Use windowSeconds |
| -2 | Key doesn't exist | Use windowSeconds |

---

## Secondary Operation: getCount(apiKey)

### Purpose

Get current count without incrementing. Used by `/usage` endpoint.

### Implementation

```java
public record UsageResult(long count, long ttlSeconds) {
    public static UsageResult EMPTY = new UsageResult(0, 0);
}

public UsageResult getCount(String apiKey) {
    String key = "ratelimit:count:" + apiKey;
    String value = redis.get(key);
    
    if (value == null) {
        return UsageResult.EMPTY;
    }
    
    Long ttl = redis.getExpire(key, TimeUnit.SECONDS);
    return new UsageResult(Long.parseLong(value), ttl != null ? ttl : 0);
}
```

---

## Operation: delete(apiKey)

### Purpose

Clear the counter when a rate limit rule is deleted.

### Implementation

```java
public void delete(String apiKey) {
    String key = "ratelimit:count:" + apiKey;
    redis.delete(key);
}
```

---

## Key Naming Convention

| Key | Pattern | Example |
|-----|---------|---------|
| Counter | `ratelimit:count:{apiKey}` | `ratelimit:count:abc-123` |

---

## Thread Safety

- Redis operations are inherently thread-safe
- Lua scripts execute atomically
- No additional synchronization needed in Java code

---

## Error Handling

| Error | Handling |
|-------|----------|
| Redis connection failure | Throw exception, let service layer handle |
| Lua script error | Should not happen with valid script; log and throw |
| Invalid count value | Should not happen; defensive parsing with fallback |
