# Rate Limiting Service Design

## Overview

A RESTful rate-limiter service that tracks API usage by key and blocks requests exceeding the allowed threshold.

**Tech Stack:** Spring Boot + MySQL + Redis + RocketMQ

---

## Project Structure

```
src/main/java/com/example/demo/
├── controller/
│   └── RateLimitController.java
├── service/
│   ├── RateLimitService.java         # Main business logic
│   └── ConfigService.java            # Rule management + cache (Caffeine/Redis)
├── ratelimiter/
│   └── RateLimiter.java              # Redis counter operations
├── event/
│   ├── EventPublisher.java           # Send messages
│   └── RateLimitEventConsumer.java   # Receive messages (logging)
├── repository/
│   └── RateLimitConfigRepository.java
├── entity/
│   └── RateLimitConfig.java
├── dto/
│   ├── request/
│   │   └── CreateLimitRequest.java
│   ├── response/
│   │   ├── CheckResponse.java
│   │   ├── UsageResponse.java
│   │   └── LimitResponse.java
│   └── event/
│       └── RateLimitEvent.java
├── exception/
│   ├── RateLimitExceededException.java
│   └── GlobalExceptionHandler.java
└── config/
    ├── RedisConfig.java
    ├── RocketMQConfig.java
    └── CacheConfig.java              # Caffeine config
```

---

## Data Models

### MySQL Table: `rate_limit_config`

| Column | Type | Description |
|--------|------|-------------|
| id | BIGINT (PK) | Auto-increment |
| api_key | VARCHAR(255) UNIQUE | API Key |
| request_limit | INT | Request limit |
| window_seconds | INT | Time window (seconds) |
| created_at | DATETIME | Created time |
| updated_at | DATETIME | Updated time |

### Redis Keys

| Key Pattern | Type | TTL | Description |
|-------------|------|-----|-------------|
| `ratelimit:count:{apiKey}` | STRING | windowSeconds | Current request count |
| `ratelimit:config:{apiKey}` | HASH | 5 min | Config cache |

### RocketMQ Event

```json
{
  "eventType": "RATE_LIMIT_EXCEEDED | CONFIG_CREATED | CONFIG_DELETED",
  "apiKey": "abc-123",
  "timestamp": "2026-04-06T12:00:00Z",
  "details": {
    "currentCount": 101,
    "limit": 100,
    "windowSeconds": 60
  }
}
```

---

## API Specification

### 1. POST /limits — Create/Update Rule

**Request:**
```json
{ "apiKey": "abc-123", "limit": 100, "windowSeconds": 60 }
```

**Validation:**
- `apiKey`: Required, non-blank
- `limit`: Required, 1 ~ 1,000,000
- `windowSeconds`: Required, 1 ~ 86400 (max one day)

**Response (201 Created):**
```json
{ "apiKey": "abc-123", "limit": 100, "windowSeconds": 60 }
```

**Validation Error (400 Bad Request):**
```json
{ "error": "Validation failed", "details": ["limit must be at least 1"] }
```

---

### 2. GET /check?apiKey=abc-123 — Check Rate Limit

**Allowed (200 OK):**

Headers:
```
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 95
X-RateLimit-Reset: 1712404800
```

Body:
```json
{ "allowed": true, "currentCount": 5, "limit": 100, "remaining": 95 }
```

**Blocked (429 Too Many Requests):**

Headers:
```
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 0
X-RateLimit-Reset: 1712404800
Retry-After: 45
```

Body:
```json
{ "allowed": false, "currentCount": 101, "limit": 100, "remaining": 0, "retryAfterSeconds": 45 }
```

> `retryAfterSeconds` and `X-RateLimit-Reset` calculated from Redis TTL

**No apiKey parameter (401 Unauthorized):**
```json
{ "error": "API key is required" }
```

**No rule configured (200 OK) — Allow:**
```json
{ "allowed": true, "message": "No rate limit configured" }
```

---

### 3. GET /usage?apiKey=abc-123 — Query Usage

**Response (200 OK):**
```json
{ "apiKey": "abc-123", "currentCount": 50, "limit": 100, "remaining": 50, "ttlSeconds": 35 }
```

---

### 4. DELETE /limits/{apiKey} — Delete Rule

**Response (204 No Content):** No body

**Not Found (404 Not Found):**
```json
{ "error": "Rate limit config not found for apiKey: abc-123" }
```

---

### 5. GET /limits?page=0&size=10 — List All Rules

**Response (200 OK):**
```json
{
  "content": [
    { "apiKey": "abc-123", "limit": 100, "windowSeconds": 60 },
    { "apiKey": "xyz-789", "limit": 50, "windowSeconds": 30 }
  ],
  "page": 0,
  "size": 10,
  "totalElements": 2,
  "totalPages": 1
}
```

---

## Core Component Flows

### ConfigService Cache Strategy (Anti Cache-Penetration)

```
getConfig(apiKey):
  1. Caffeine.get(apiKey) → return if found (including null object)
  2. Redis.get(ratelimit:config:{apiKey}) → store in Caffeine and return if found
  3. MySQL.findByApiKey(apiKey)
     └→ Found → store in Redis(TTL=5min) + Caffeine and return
     └→ Not found → store null object in Caffeine(TTL=30s) and return null

createConfig(request):
  1. MySQL.save()
  2. Redis.set(ratelimit:config:{apiKey}, config, TTL=5min)
  3. Caffeine.invalidate(apiKey)
  4. EventPublisher.publish(CONFIG_CREATED)

deleteConfig(apiKey):
  1. MySQL.delete()
  2. Redis.delete(ratelimit:config:{apiKey})
  3. Redis.delete(ratelimit:count:{apiKey})  // Also clear counter
  4. Caffeine.invalidate(apiKey)
  5. EventPublisher.publish(CONFIG_DELETED)
```

### RateLimiter.increment (Handle TTL Edge Cases)

```lua
-- Redis Lua Script (Atomic operation)
local count = redis.call('INCR', KEYS[1])
if count == 1 then
  redis.call('EXPIRE', KEYS[1], ARGV[1])
end
local ttl = redis.call('TTL', KEYS[1])
-- Handle edge cases: ttl = -1 (no expiry) or -2 (key doesn't exist)
if ttl < 0 then
  ttl = tonumber(ARGV[1])
end
return {count, ttl}
```

### RateLimitService.check (Event Timing)

```
1. Validate apiKey non-empty → throw 401 if empty

2. ConfigService.getConfig(apiKey)
   └→ Not found → return { allowed: true, message: "No rate limit configured" }

3. RateLimiter.increment(apiKey, windowSeconds)
   └→ return { count, ttl }

4. Check count > limit?
   └→ Yes → EventPublisher.publish(RATE_LIMIT_EXCEEDED)  // Send event first
          → throw RateLimitExceededException(count, limit, ttl)
   └→ No  → return CheckResponse(...)
```

> Event is sent **before** throwing exception to ensure it's always recorded.

---

## Testing Strategy

### Unit Tests (Mock Dependencies)

| Class | Test Focus |
|-------|------------|
| `RateLimitServiceTest` | check logic: no rule allow, pass, blocked, empty apiKey 401 |
| `ConfigServiceTest` | Cache hit order: Caffeine → Redis → MySQL, null object cache |
| `RateLimiterTest` | increment returns count/ttl, TTL edge value handling |
| `EventPublisherTest` | Message format correct, send success |

### Integration Tests (Testcontainers)

| Test Class | Coverage |
|------------|----------|
| `RateLimitControllerIT` | Full API end-to-end tests |

**Test Cases:**

```
POST /limits
  ✓ Create new rule → 201
  ✓ Update existing rule → 201
  ✓ Validation failed (limit=0) → 400

GET /check
  ✓ No apiKey parameter → 401
  ✓ No rule → 200 allow
  ✓ Under limit → 200 + correct headers
  ✓ Over limit → 429 + Retry-After header

GET /usage
  ✓ Normal query → 200
  ✓ No such apiKey → 200 (count=0)

DELETE /limits/{apiKey}
  ✓ Delete success → 204
  ✓ Not found → 404

GET /limits
  ✓ Paginated query → 200
  ✓ Empty data → 200 (empty array)
```

### Test Dependencies

```xml
<!-- pom.xml additions -->
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>mysql</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>junit-jupiter</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>com.redis</groupId>
    <artifactId>testcontainers-redis</artifactId>
    <version>2.2.2</version>
    <scope>test</scope>
</dependency>
```

---

## Design Decisions

| Decision | Reasoning |
|----------|-----------|
| No rule → Allow | Explicit whitelist approach, no default blocking |
| 429 for blocked | Standard HTTP semantics for rate limiting |
| Caffeine + Redis cache | Local cache reduces Redis calls; Redis shared across instances |
| Null object cache (30s) | Prevents cache penetration from invalid apiKeys |
| Event before exception | Ensures event is always published even if exception handling changes |
| Lua script for INCR | Atomic operation prevents race conditions |

---

## RocketMQ Usage

- **RATE_LIMIT_EXCEEDED**: When a request is blocked (count > limit)
- **CONFIG_CREATED**: When a new rate limit rule is created
- **CONFIG_DELETED**: When a rate limit rule is deleted

Consumer logs these events for auditing and analysis.
