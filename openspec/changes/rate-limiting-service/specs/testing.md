# Testing Specification

## Overview

Two-tier testing strategy: unit tests for isolated logic, integration tests for end-to-end flows.

---

## Unit Tests

### RateLimitServiceTest

| Test Case | Description |
|-----------|-------------|
| `check_noApiKey_returns401` | Empty/null apiKey throws UnauthorizedException |
| `check_noRule_allowsRequest` | Missing config returns allowed=true with message |
| `check_underLimit_allowsRequest` | count < limit returns allowed=true |
| `check_atLimit_blocksRequest` | count > limit throws RateLimitExceededException |
| `check_exactlyAtLimit_allowsRequest` | count == limit returns allowed=true |
| `check_blocked_publishesEvent` | Verify EventPublisher called before exception |

**Mocks:** ConfigService, RateLimiter, EventPublisher

### ConfigServiceTest

| Test Case | Description |
|-----------|-------------|
| `getConfig_caffeineHit_returnsFromCache` | Caffeine hit, no Redis/MySQL call |
| `getConfig_redisHit_storesCaffeine` | Redis hit, stores in Caffeine |
| `getConfig_mysqlHit_storesBothCaches` | MySQL hit, stores in Redis + Caffeine |
| `getConfig_notFound_cachesNullObject` | Missing key caches null object |
| `createConfig_invalidatesCache` | Create clears Caffeine |
| `deleteConfig_clearsAllCaches` | Delete clears Caffeine + both Redis keys |

**Mocks:** CaffeineCache, RedisTemplate, Repository

### RateLimiterTest

| Test Case | Description |
|-----------|-------------|
| `increment_firstRequest_setsExpiry` | count=1 sets TTL |
| `increment_subsequentRequest_noExpiryChange` | count>1 keeps existing TTL |
| `increment_returnsTtl` | Returns correct TTL from Redis |
| `increment_negativeTtl_usesWindowSeconds` | TTL -1/-2 returns windowSeconds |
| `getCount_noKey_returnsZero` | Missing key returns count=0 |
| `delete_removesKey` | Key is deleted |

**Mocks:** RedisTemplate (or embedded Redis)

### EventPublisherTest

| Test Case | Description |
|-----------|-------------|
| `publish_exceeded_correctFormat` | RATE_LIMIT_EXCEEDED message format |
| `publish_created_correctFormat` | CONFIG_CREATED message format |
| `publish_deleted_correctFormat` | CONFIG_DELETED message format |

**Mocks:** RocketMQTemplate

---

## Integration Tests

### RateLimitControllerIT

Uses Testcontainers for MySQL and Redis.

```java
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
class RateLimitControllerIT {
    
    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");
    
    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7")
        .withExposedPorts(6379);
}
```

### Test Cases

#### POST /limits

| Test Case | Expected |
|-----------|----------|
| Create new rule | 201, body matches request |
| Update existing rule | 201, values updated |
| Invalid limit (0) | 400, validation error |
| Invalid windowSeconds (0) | 400, validation error |
| Missing apiKey | 400, validation error |

#### GET /check

| Test Case | Expected |
|-----------|----------|
| No apiKey param | 401 |
| No rule exists | 200, allowed=true, message |
| Under limit | 200, allowed=true, correct remaining |
| Over limit | 429, correct headers |
| Verify X-RateLimit headers | Headers present and correct |
| Verify Retry-After header | Present when blocked |

#### GET /usage

| Test Case | Expected |
|-----------|----------|
| Existing key with usage | 200, correct count/remaining/ttl |
| No usage yet | 200, count=0 |

#### DELETE /limits/{apiKey}

| Test Case | Expected |
|-----------|----------|
| Existing rule | 204, no body |
| Non-existent rule | 404 |
| Verify counter cleared | Redis counter key deleted |

#### GET /limits

| Test Case | Expected |
|-----------|----------|
| With data | 200, paginated response |
| Empty | 200, empty content array |
| Page 2 | 200, correct offset |

---

## Test Dependencies

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>testcontainers</artifactId>
    <scope>test</scope>
</dependency>
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

## Test Configuration

### application-test.yaml

```yaml
spring:
  datasource:
    url: jdbc:tc:mysql:8.0:///testdb
    driver-class-name: org.testcontainers.jdbc.ContainerDatabaseDriver
  jpa:
    hibernate:
      ddl-auto: create-drop
```

---

## Coverage Goals

| Layer | Target |
|-------|--------|
| Service | 90%+ |
| RateLimiter | 90%+ |
| Controller | 80%+ (via integration tests) |
| Repository | Covered by integration tests |
