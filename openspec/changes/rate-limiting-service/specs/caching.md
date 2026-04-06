# Caching Specification

## Overview

Three-tier caching strategy to minimize database load while maintaining consistency.

```
┌────────────────┐     ┌────────────────┐     ┌────────────────┐
│    Caffeine    │     │     Redis      │     │     MySQL      │
│  (Local JVM)   │────▶│   (Shared)     │────▶│  (Persistent)  │
│   TTL: varies  │     │   TTL: 5min    │     │   Source of    │
│                │     │                │     │     Truth      │
└────────────────┘     └────────────────┘     └────────────────┘
```

---

## Read Path: getConfig(apiKey)

```
1. Check Caffeine cache
   └─ HIT  → return (includes null objects)
   └─ MISS → continue

2. Check Redis cache
   └─ HIT  → store in Caffeine → return
   └─ MISS → continue

3. Query MySQL
   └─ FOUND     → store in Redis (5min) → store in Caffeine → return
   └─ NOT FOUND → store null object in Caffeine (30s) → return null
```

---

## Write Path: createConfig(request)

```
1. Upsert to MySQL
2. Set Redis cache (ratelimit:config:{apiKey}, TTL=5min)
3. Invalidate Caffeine cache
4. Publish CONFIG_CREATED event
```

---

## Delete Path: deleteConfig(apiKey)

```
1. Delete from MySQL
2. Delete Redis config cache (ratelimit:config:{apiKey})
3. Delete Redis counter (ratelimit:count:{apiKey})
4. Invalidate Caffeine cache
5. Publish CONFIG_DELETED event
```

---

## Cache Configuration

### Caffeine (Local)

```java
Caffeine.newBuilder()
    .maximumSize(10_000)
    .expireAfterWrite(Duration.ofMinutes(1))
    .build();
```

| Setting | Value | Reasoning |
|---------|-------|-----------|
| Max size | 10,000 | Reasonable for most deployments |
| Default TTL | 1 minute | Balance between freshness and performance |
| Null object TTL | 30 seconds | Short to allow quick recovery |

### Redis Config Cache

| Setting | Value |
|---------|-------|
| Key pattern | `ratelimit:config:{apiKey}` |
| TTL | 5 minutes |
| Type | HASH with fields: `limit`, `windowSeconds` |

---

## Cache Penetration Protection

**Problem:** Repeated queries for non-existent apiKeys hit MySQL every time.

**Solution:** Cache null objects in Caffeine with short TTL.

```java
// Pseudo-code
Config config = caffeine.get(apiKey, key -> {
    Config fromRedis = redis.get(key);
    if (fromRedis != null) return fromRedis;
    
    Config fromDb = mysql.findByApiKey(key);
    if (fromDb != null) {
        redis.set(key, fromDb, Duration.ofMinutes(5));
        return fromDb;
    }
    
    // Cache null object to prevent penetration
    return NullConfig.INSTANCE;  // TTL: 30s
});

if (config == NullConfig.INSTANCE) {
    return null;
}
return config;
```

---

## Consistency Considerations

| Scenario | Behavior |
|----------|----------|
| Config updated | Redis + Caffeine invalidated immediately |
| Config deleted | All caches + counter cleared |
| Instance restart | Caffeine empty, rebuilds from Redis/MySQL |
| Redis restart | Rebuilds from MySQL on cache miss |

**Trade-off:** Brief staleness (up to 1 min Caffeine, 5 min Redis) is acceptable for this use case. Rate limit rules don't change frequently.
