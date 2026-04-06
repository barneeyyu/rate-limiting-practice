# Rate Limiting Service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a RESTful rate-limiting service that tracks API usage by key and blocks requests exceeding configured thresholds.

**Architecture:** Three-layer design with Controller → Service → Infrastructure. ConfigService manages rules with Caffeine→Redis→MySQL caching. RateLimiter handles Redis counters via Lua script. EventPublisher sends RocketMQ messages on block/config changes.

**Tech Stack:** Spring Boot 3.5, MySQL 8, Redis 7, RocketMQ 5.1, Caffeine, Testcontainers

---

## File Structure

```
src/main/java/com/example/demo/
├── config/
│   ├── RedisConfig.java              # Redis configuration + Lua script bean
│   ├── RocketMQConfig.java           # RocketMQ producer configuration
│   └── CacheConfig.java              # Caffeine cache configuration
├── entity/
│   └── RateLimitConfig.java          # JPA entity for rate_limit_config table
├── repository/
│   └── RateLimitConfigRepository.java # Spring Data JPA repository
├── dto/
│   ├── request/
│   │   └── CreateLimitRequest.java   # POST /limits request body
│   └── response/
│       ├── CheckResponse.java        # GET /check response
│       ├── UsageResponse.java        # GET /usage response
│       ├── LimitResponse.java        # Single limit in list
│       ├── PagedLimitResponse.java   # GET /limits paginated response
│       └── ErrorResponse.java        # Error response format
├── event/
│   ├── RateLimitEvent.java           # Event DTO for RocketMQ
│   ├── EventPublisher.java           # Sends events to RocketMQ
│   └── RateLimitEventConsumer.java   # Consumes events (logging)
├── ratelimiter/
│   ├── RateLimiter.java              # Redis counter operations
│   └── IncrementResult.java          # Result record for increment
├── service/
│   ├── ConfigService.java            # Config CRUD + caching
│   └── RateLimitService.java         # Main business logic
├── exception/
│   ├── RateLimitExceededException.java
│   ├── ApiKeyRequiredException.java
│   ├── ConfigNotFoundException.java
│   └── GlobalExceptionHandler.java   # @ControllerAdvice
└── controller/
    └── RateLimitController.java      # REST endpoints

src/main/resources/
├── application.yaml                  # Main config
└── scripts/
    └── increment.lua                 # Redis Lua script

src/test/java/com/example/demo/
├── ratelimiter/
│   └── RateLimiterTest.java
├── service/
│   ├── ConfigServiceTest.java
│   └── RateLimitServiceTest.java
├── event/
│   └── EventPublisherTest.java
└── controller/
    └── RateLimitControllerIT.java    # Integration tests

init.sql                              # MySQL schema
```

---

## Task 1: Project Setup - Dependencies

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: Add required dependencies to pom.xml**

Add these dependencies inside the `<dependencies>` section:

```xml
<!-- Web -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>

<!-- Validation -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
</dependency>

<!-- JPA -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>

<!-- MySQL -->
<dependency>
    <groupId>com.mysql</groupId>
    <artifactId>mysql-connector-j</artifactId>
    <scope>runtime</scope>
</dependency>

<!-- Caffeine Cache -->
<dependency>
    <groupId>com.github.ben-manes.caffeine</groupId>
    <artifactId>caffeine</artifactId>
</dependency>

<!-- Testcontainers -->
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

Add Testcontainers BOM in `<dependencyManagement>`:

```xml
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>testcontainers-bom</artifactId>
    <version>1.19.3</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>
```

- [ ] **Step 2: Verify dependencies resolve**

Run: `./mvnw dependency:resolve`

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add pom.xml
git commit -m "chore: add dependencies for rate limiting service

Add Spring Web, Validation, JPA, MySQL, Caffeine, and Testcontainers"
```

---

## Task 2: Database Schema

**Files:**
- Modify: `init.sql`

- [ ] **Step 1: Write the MySQL schema**

```sql
CREATE TABLE IF NOT EXISTS rate_limit_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    api_key VARCHAR(255) NOT NULL UNIQUE,
    request_limit INT NOT NULL,
    window_seconds INT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_api_key (api_key)
);
```

- [ ] **Step 2: Verify schema syntax**

Run: `docker-compose up -d mysql && sleep 5 && docker exec mysql mysql -utaskuser -ptaskpass taskdb -e "SHOW TABLES;"`

Expected: `rate_limit_config` table listed

- [ ] **Step 3: Commit**

```bash
git add init.sql
git commit -m "feat: add rate_limit_config table schema"
```

---

## Task 3: Application Configuration

**Files:**
- Modify: `src/main/resources/application.yaml`

- [ ] **Step 1: Write complete application configuration**

```yaml
spring:
  application:
    name: rate-limiting-service

  datasource:
    url: jdbc:mysql://localhost:3306/taskdb?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC
    username: taskuser
    password: taskpass
    driver-class-name: com.mysql.cj.jdbc.Driver

  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: false
    properties:
      hibernate:
        dialect: org.hibernate.dialect.MySQLDialect

  data:
    redis:
      host: localhost
      port: 6379

rocketmq:
  name-server: localhost:9876
  producer:
    group: rate-limit-producer-group
    topic: rate-limit-events

rate-limit:
  cache:
    caffeine-max-size: 10000
    caffeine-ttl-seconds: 60
    null-object-ttl-seconds: 30
    redis-config-ttl-seconds: 300
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/application.yaml
git commit -m "feat: add application configuration for MySQL, Redis, RocketMQ"
```

---

## Task 4: JPA Entity

**Files:**
- Create: `src/main/java/com/example/demo/entity/RateLimitConfig.java`

- [ ] **Step 1: Create the entity class**

```java
package com.example.demo.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "rate_limit_config")
public class RateLimitConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "api_key", nullable = false, unique = true)
    private String apiKey;

    @Column(name = "request_limit", nullable = false)
    private Integer requestLimit;

    @Column(name = "window_seconds", nullable = false)
    private Integer windowSeconds;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public RateLimitConfig() {}

    public RateLimitConfig(String apiKey, Integer requestLimit, Integer windowSeconds) {
        this.apiKey = apiKey;
        this.requestLimit = requestLimit;
        this.windowSeconds = windowSeconds;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public Integer getRequestLimit() { return requestLimit; }
    public void setRequestLimit(Integer requestLimit) { this.requestLimit = requestLimit; }

    public Integer getWindowSeconds() { return windowSeconds; }
    public void setWindowSeconds(Integer windowSeconds) { this.windowSeconds = windowSeconds; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/example/demo/entity/
git commit -m "feat: add RateLimitConfig JPA entity"
```

---

## Task 5: Repository

**Files:**
- Create: `src/main/java/com/example/demo/repository/RateLimitConfigRepository.java`

- [ ] **Step 1: Create the repository interface**

```java
package com.example.demo.repository;

import com.example.demo.entity.RateLimitConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RateLimitConfigRepository extends JpaRepository<RateLimitConfig, Long> {
    Optional<RateLimitConfig> findByApiKey(String apiKey);
    boolean existsByApiKey(String apiKey);
    void deleteByApiKey(String apiKey);
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/example/demo/repository/
git commit -m "feat: add RateLimitConfigRepository"
```

---

## Task 6: DTOs

**Files:**
- Create: `src/main/java/com/example/demo/dto/request/CreateLimitRequest.java`
- Create: `src/main/java/com/example/demo/dto/response/CheckResponse.java`
- Create: `src/main/java/com/example/demo/dto/response/UsageResponse.java`
- Create: `src/main/java/com/example/demo/dto/response/LimitResponse.java`
- Create: `src/main/java/com/example/demo/dto/response/PagedLimitResponse.java`
- Create: `src/main/java/com/example/demo/dto/response/ErrorResponse.java`

- [ ] **Step 1: Create CreateLimitRequest**

```java
package com.example.demo.dto.request;

import jakarta.validation.constraints.*;

public record CreateLimitRequest(
    @NotBlank(message = "apiKey is required")
    String apiKey,

    @NotNull(message = "limit is required")
    @Min(value = 1, message = "limit must be at least 1")
    @Max(value = 1000000, message = "limit must not exceed 1000000")
    Integer limit,

    @NotNull(message = "windowSeconds is required")
    @Min(value = 1, message = "windowSeconds must be at least 1")
    @Max(value = 86400, message = "windowSeconds must not exceed 86400")
    Integer windowSeconds
) {}
```

- [ ] **Step 2: Create CheckResponse**

```java
package com.example.demo.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CheckResponse(
    boolean allowed,
    Long currentCount,
    Integer limit,
    Long remaining,
    Long retryAfterSeconds,
    String message
) {
    public static CheckResponse allowed(long currentCount, int limit, long remaining) {
        return new CheckResponse(true, currentCount, limit, remaining, null, null);
    }

    public static CheckResponse blocked(long currentCount, int limit, long retryAfterSeconds) {
        return new CheckResponse(false, currentCount, limit, 0L, retryAfterSeconds, null);
    }

    public static CheckResponse noRuleConfigured() {
        return new CheckResponse(true, null, null, null, null, "No rate limit configured");
    }
}
```

- [ ] **Step 3: Create UsageResponse**

```java
package com.example.demo.dto.response;

public record UsageResponse(
    String apiKey,
    long currentCount,
    Integer limit,
    Long remaining,
    long ttlSeconds
) {
    public static UsageResponse noUsage(String apiKey) {
        return new UsageResponse(apiKey, 0, null, null, 0);
    }

    public static UsageResponse withUsage(String apiKey, long count, int limit, long ttl) {
        return new UsageResponse(apiKey, count, limit, Math.max(0, limit - count), ttl);
    }
}
```

- [ ] **Step 4: Create LimitResponse**

```java
package com.example.demo.dto.response;

public record LimitResponse(
    String apiKey,
    int limit,
    int windowSeconds
) {}
```

- [ ] **Step 5: Create PagedLimitResponse**

```java
package com.example.demo.dto.response;

import java.util.List;

public record PagedLimitResponse(
    List<LimitResponse> content,
    int page,
    int size,
    long totalElements,
    int totalPages
) {}
```

- [ ] **Step 6: Create ErrorResponse**

```java
package com.example.demo.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
    String error,
    List<String> details
) {
    public static ErrorResponse of(String error) {
        return new ErrorResponse(error, null);
    }

    public static ErrorResponse withDetails(String error, List<String> details) {
        return new ErrorResponse(error, details);
    }
}
```

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/example/demo/dto/
git commit -m "feat: add request and response DTOs"
```

---

## Task 7: Exception Classes

**Files:**
- Create: `src/main/java/com/example/demo/exception/RateLimitExceededException.java`
- Create: `src/main/java/com/example/demo/exception/ApiKeyRequiredException.java`
- Create: `src/main/java/com/example/demo/exception/ConfigNotFoundException.java`

- [ ] **Step 1: Create RateLimitExceededException**

```java
package com.example.demo.exception;

public class RateLimitExceededException extends RuntimeException {
    private final long currentCount;
    private final int limit;
    private final long retryAfterSeconds;

    public RateLimitExceededException(long currentCount, int limit, long retryAfterSeconds) {
        super("Rate limit exceeded");
        this.currentCount = currentCount;
        this.limit = limit;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getCurrentCount() { return currentCount; }
    public int getLimit() { return limit; }
    public long getRetryAfterSeconds() { return retryAfterSeconds; }
}
```

- [ ] **Step 2: Create ApiKeyRequiredException**

```java
package com.example.demo.exception;

public class ApiKeyRequiredException extends RuntimeException {
    public ApiKeyRequiredException() {
        super("API key is required");
    }
}
```

- [ ] **Step 3: Create ConfigNotFoundException**

```java
package com.example.demo.exception;

public class ConfigNotFoundException extends RuntimeException {
    public ConfigNotFoundException(String apiKey) {
        super("Rate limit config not found for apiKey: " + apiKey);
    }
}
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/example/demo/exception/
git commit -m "feat: add custom exception classes"
```

---

## Task 8: Global Exception Handler

**Files:**
- Create: `src/main/java/com/example/demo/exception/GlobalExceptionHandler.java`

- [ ] **Step 1: Create the exception handler**

```java
package com.example.demo.exception;

import com.example.demo.dto.response.CheckResponse;
import com.example.demo.dto.response.ErrorResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<CheckResponse> handleRateLimitExceeded(RateLimitExceededException ex) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-RateLimit-Limit", String.valueOf(ex.getLimit()));
        headers.set("X-RateLimit-Remaining", "0");
        headers.set("X-RateLimit-Reset", String.valueOf(Instant.now().getEpochSecond() + ex.getRetryAfterSeconds()));
        headers.set("Retry-After", String.valueOf(ex.getRetryAfterSeconds()));

        CheckResponse response = CheckResponse.blocked(ex.getCurrentCount(), ex.getLimit(), ex.getRetryAfterSeconds());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).headers(headers).body(response);
    }

    @ExceptionHandler(ApiKeyRequiredException.class)
    public ResponseEntity<ErrorResponse> handleApiKeyRequired(ApiKeyRequiredException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ErrorResponse.of(ex.getMessage()));
    }

    @ExceptionHandler(ConfigNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleConfigNotFound(ConfigNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of(ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
            .map(error -> error.getDefaultMessage())
            .toList();
        return ResponseEntity.badRequest().body(ErrorResponse.withDetails("Validation failed", details));
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/example/demo/exception/GlobalExceptionHandler.java
git commit -m "feat: add global exception handler"
```

---

## Task 9: Redis Configuration

**Files:**
- Create: `src/main/java/com/example/demo/config/RedisConfig.java`
- Create: `src/main/resources/scripts/increment.lua`

- [ ] **Step 1: Create the Lua script**

```lua
local count = redis.call('INCR', KEYS[1])

if count == 1 then
    redis.call('EXPIRE', KEYS[1], ARGV[1])
end

local ttl = redis.call('TTL', KEYS[1])

if ttl < 0 then
    ttl = tonumber(ARGV[1])
end

return {count, ttl}
```

- [ ] **Step 2: Create RedisConfig**

```java
package com.example.demo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.scripting.support.ResourceScriptSource;

import java.util.List;

@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new StringRedisSerializer());
        return template;
    }

    @Bean
    public DefaultRedisScript<List> incrementScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("scripts/increment.lua")));
        script.setResultType(List.class);
        return script;
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/scripts/ src/main/java/com/example/demo/config/RedisConfig.java
git commit -m "feat: add Redis configuration and Lua script"
```

---

## Task 10: Caffeine Cache Configuration

**Files:**
- Create: `src/main/java/com/example/demo/config/CacheConfig.java`

- [ ] **Step 1: Create CacheConfig**

```java
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
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/example/demo/config/CacheConfig.java
git commit -m "feat: add Caffeine cache configuration"
```

---

## Task 11: RocketMQ Configuration

**Files:**
- Create: `src/main/java/com/example/demo/config/RocketMQConfig.java`

- [ ] **Step 1: Create RocketMQConfig**

```java
package com.example.demo.config;

import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RocketMQConfig {

    @Value("${rocketmq.name-server}")
    private String nameServer;

    @Value("${rocketmq.producer.group}")
    private String producerGroup;

    @Bean
    public DefaultMQProducer defaultMQProducer() throws Exception {
        DefaultMQProducer producer = new DefaultMQProducer(producerGroup);
        producer.setNamesrvAddr(nameServer);
        producer.start();
        return producer;
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/example/demo/config/RocketMQConfig.java
git commit -m "feat: add RocketMQ producer configuration"
```

---

## Task 12: RateLimiter Component

**Files:**
- Create: `src/main/java/com/example/demo/ratelimiter/IncrementResult.java`
- Create: `src/main/java/com/example/demo/ratelimiter/RateLimiter.java`

- [ ] **Step 1: Create IncrementResult record**

```java
package com.example.demo.ratelimiter;

public record IncrementResult(long count, long ttlSeconds) {}
```

- [ ] **Step 2: Create RateLimiter**

```java
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
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/example/demo/ratelimiter/
git commit -m "feat: add RateLimiter component with Lua script"
```

---

## Task 13: Event Publisher

**Files:**
- Create: `src/main/java/com/example/demo/event/RateLimitEvent.java`
- Create: `src/main/java/com/example/demo/event/EventPublisher.java`

- [ ] **Step 1: Create RateLimitEvent**

```java
package com.example.demo.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record RateLimitEvent(
    EventType eventType,
    String apiKey,
    String timestamp,
    Map<String, Object> details
) {
    public enum EventType {
        RATE_LIMIT_EXCEEDED,
        CONFIG_CREATED,
        CONFIG_DELETED
    }

    public static RateLimitEvent exceeded(String apiKey, long currentCount, int limit, int windowSeconds) {
        return new RateLimitEvent(
            EventType.RATE_LIMIT_EXCEEDED,
            apiKey,
            Instant.now().toString(),
            Map.of("currentCount", currentCount, "limit", limit, "windowSeconds", windowSeconds)
        );
    }

    public static RateLimitEvent configCreated(String apiKey, int limit, int windowSeconds) {
        return new RateLimitEvent(
            EventType.CONFIG_CREATED,
            apiKey,
            Instant.now().toString(),
            Map.of("limit", limit, "windowSeconds", windowSeconds)
        );
    }

    public static RateLimitEvent configDeleted(String apiKey) {
        return new RateLimitEvent(
            EventType.CONFIG_DELETED,
            apiKey,
            Instant.now().toString(),
            null
        );
    }
}
```

- [ ] **Step 2: Create EventPublisher**

```java
package com.example.demo.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.message.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);

    private final DefaultMQProducer producer;
    private final ObjectMapper objectMapper;
    private final String topic;

    public EventPublisher(
            DefaultMQProducer producer,
            ObjectMapper objectMapper,
            @Value("${rocketmq.producer.topic}") String topic) {
        this.producer = producer;
        this.objectMapper = objectMapper;
        this.topic = topic;
    }

    public void publish(RateLimitEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            Message message = new Message(topic, json.getBytes(StandardCharsets.UTF_8));
            producer.send(message);
            log.info("Published event: {} for apiKey: {}", event.eventType(), event.apiKey());
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize event", e);
        } catch (Exception e) {
            log.error("Failed to publish event", e);
        }
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/example/demo/event/
git commit -m "feat: add RocketMQ event publisher"
```

---

## Task 14: Event Consumer

**Files:**
- Create: `src/main/java/com/example/demo/event/RateLimitEventConsumer.java`

- [ ] **Step 1: Create RateLimitEventConsumer**

```java
package com.example.demo.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.message.MessageExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;

@Component
public class RateLimitEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(RateLimitEventConsumer.class);

    private final ObjectMapper objectMapper;
    private final String nameServer;
    private final String topic;
    private DefaultMQPushConsumer consumer;

    public RateLimitEventConsumer(
            ObjectMapper objectMapper,
            @Value("${rocketmq.name-server}") String nameServer,
            @Value("${rocketmq.producer.topic}") String topic) {
        this.objectMapper = objectMapper;
        this.nameServer = nameServer;
        this.topic = topic;
    }

    @PostConstruct
    public void start() throws Exception {
        consumer = new DefaultMQPushConsumer("rate-limit-consumer-group");
        consumer.setNamesrvAddr(nameServer);
        consumer.subscribe(topic, "*");

        consumer.registerMessageListener((MessageListenerConcurrently) (messages, context) -> {
            for (MessageExt msg : messages) {
                try {
                    String json = new String(msg.getBody(), StandardCharsets.UTF_8);
                    RateLimitEvent event = objectMapper.readValue(json, RateLimitEvent.class);
                    log.info("Received event: {} for apiKey: {}", event.eventType(), event.apiKey());
                } catch (Exception e) {
                    log.error("Failed to process message", e);
                }
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });

        consumer.start();
        log.info("RateLimitEventConsumer started");
    }

    @PreDestroy
    public void stop() {
        if (consumer != null) {
            consumer.shutdown();
            log.info("RateLimitEventConsumer stopped");
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/example/demo/event/RateLimitEventConsumer.java
git commit -m "feat: add RocketMQ event consumer"
```

---

## Task 15: ConfigService

**Files:**
- Create: `src/main/java/com/example/demo/service/ConfigService.java`

- [ ] **Step 1: Create ConfigService**

```java
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
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/example/demo/service/ConfigService.java
git commit -m "feat: add ConfigService with multi-layer caching"
```

---

## Task 16: RateLimitService

**Files:**
- Create: `src/main/java/com/example/demo/service/RateLimitService.java`

- [ ] **Step 1: Create RateLimitService**

```java
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
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/example/demo/service/RateLimitService.java
git commit -m "feat: add RateLimitService with check and usage logic"
```

---

## Task 17: Controller

**Files:**
- Create: `src/main/java/com/example/demo/controller/RateLimitController.java`

- [ ] **Step 1: Create RateLimitController**

```java
package com.example.demo.controller;

import com.example.demo.dto.request.CreateLimitRequest;
import com.example.demo.dto.response.*;
import com.example.demo.entity.RateLimitConfig;
import com.example.demo.service.ConfigService;
import com.example.demo.service.RateLimitService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
public class RateLimitController {

    private final RateLimitService rateLimitService;
    private final ConfigService configService;

    public RateLimitController(RateLimitService rateLimitService, ConfigService configService) {
        this.rateLimitService = rateLimitService;
        this.configService = configService;
    }

    @PostMapping("/limits")
    public ResponseEntity<LimitResponse> createLimit(@Valid @RequestBody CreateLimitRequest request) {
        RateLimitConfig config = configService.createOrUpdate(
            request.apiKey(), request.limit(), request.windowSeconds());

        LimitResponse response = new LimitResponse(
            config.getApiKey(), config.getRequestLimit(), config.getWindowSeconds());

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/check")
    public ResponseEntity<CheckResponse> check(@RequestParam(required = false) String apiKey) {
        CheckResponse response = rateLimitService.check(apiKey);

        HttpHeaders headers = new HttpHeaders();
        if (response.limit() != null) {
            headers.set("X-RateLimit-Limit", String.valueOf(response.limit()));
            headers.set("X-RateLimit-Remaining", String.valueOf(response.remaining()));
            headers.set("X-RateLimit-Reset", String.valueOf(Instant.now().getEpochSecond() + 60));
        }

        return ResponseEntity.ok().headers(headers).body(response);
    }

    @GetMapping("/usage")
    public ResponseEntity<UsageResponse> usage(@RequestParam String apiKey) {
        UsageResponse response = rateLimitService.getUsage(apiKey);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/limits/{apiKey}")
    public ResponseEntity<Void> deleteLimit(@PathVariable String apiKey) {
        configService.delete(apiKey);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/limits")
    public ResponseEntity<PagedLimitResponse> listLimits(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        Page<RateLimitConfig> pageResult = configService.findAll(PageRequest.of(page, size));

        List<LimitResponse> content = pageResult.getContent().stream()
            .map(c -> new LimitResponse(c.getApiKey(), c.getRequestLimit(), c.getWindowSeconds()))
            .toList();

        PagedLimitResponse response = new PagedLimitResponse(
            content,
            pageResult.getNumber(),
            pageResult.getSize(),
            pageResult.getTotalElements(),
            pageResult.getTotalPages()
        );

        return ResponseEntity.ok(response);
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/example/demo/controller/
git commit -m "feat: add RateLimitController with all endpoints"
```

---

## Task 18: Unit Tests - RateLimiterTest

**Files:**
- Create: `src/test/java/com/example/demo/ratelimiter/RateLimiterTest.java`

- [ ] **Step 1: Create RateLimiterTest**

```java
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
```

- [ ] **Step 2: Run the test**

Run: `./mvnw test -Dtest=RateLimiterTest -q`

Expected: Tests pass

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/example/demo/ratelimiter/
git commit -m "test: add RateLimiter unit tests"
```

---

## Task 19: Unit Tests - RateLimitServiceTest

**Files:**
- Create: `src/test/java/com/example/demo/service/RateLimitServiceTest.java`

- [ ] **Step 1: Create RateLimitServiceTest**

```java
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
```

- [ ] **Step 2: Run the tests**

Run: `./mvnw test -Dtest=RateLimitServiceTest -q`

Expected: Tests pass

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/example/demo/service/RateLimitServiceTest.java
git commit -m "test: add RateLimitService unit tests"
```

---

## Task 20: Integration Tests

**Files:**
- Create: `src/test/java/com/example/demo/controller/RateLimitControllerIT.java`
- Create: `src/test/resources/application-test.yaml`

- [ ] **Step 1: Create test configuration**

```yaml
spring:
  datasource:
    driver-class-name: org.testcontainers.jdbc.ContainerDatabaseDriver

  jpa:
    hibernate:
      ddl-auto: create-drop

rocketmq:
  name-server: localhost:9876
  producer:
    group: test-producer-group
    topic: test-rate-limit-events

rate-limit:
  cache:
    caffeine-max-size: 100
    caffeine-ttl-seconds: 60
    null-object-ttl-seconds: 30
    redis-config-ttl-seconds: 300
```

- [ ] **Step 2: Create integration test**

```java
package com.example.demo.controller;

import com.example.demo.dto.request.CreateLimitRequest;
import com.example.demo.dto.response.CheckResponse;
import com.example.demo.dto.response.LimitResponse;
import com.example.demo.dto.response.PagedLimitResponse;
import com.example.demo.dto.response.UsageResponse;
import com.example.demo.event.EventPublisher;
import com.example.demo.event.RateLimitEventConsumer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class RateLimitControllerIT {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
        .withDatabaseName("testdb")
        .withUsername("test")
        .withPassword("test");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7"))
        .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @MockBean
    private EventPublisher eventPublisher;

    @MockBean
    private RateLimitEventConsumer eventConsumer;

    @BeforeEach
    void setUp() {
        redisTemplate.getConnectionFactory().getConnection().flushAll();
    }

    @Test
    void createLimit_validRequest_returns201() throws Exception {
        CreateLimitRequest request = new CreateLimitRequest("test-api-key", 100, 60);

        mockMvc.perform(post("/limits")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.apiKey").value("test-api-key"))
            .andExpect(jsonPath("$.limit").value(100))
            .andExpect(jsonPath("$.windowSeconds").value(60));
    }

    @Test
    void createLimit_invalidLimit_returns400() throws Exception {
        CreateLimitRequest request = new CreateLimitRequest("test-api-key", 0, 60);

        mockMvc.perform(post("/limits")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Validation failed"))
            .andExpect(jsonPath("$.details", hasItem("limit must be at least 1")));
    }

    @Test
    void check_noApiKey_returns401() throws Exception {
        mockMvc.perform(get("/check"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("API key is required"));
    }

    @Test
    void check_noRuleConfigured_returns200Allowed() throws Exception {
        mockMvc.perform(get("/check").param("apiKey", "unknown-key"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.allowed").value(true))
            .andExpect(jsonPath("$.message").value("No rate limit configured"));
    }

    @Test
    void check_underLimit_returns200WithHeaders() throws Exception {
        CreateLimitRequest request = new CreateLimitRequest("check-test-key", 100, 60);
        mockMvc.perform(post("/limits")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated());

        mockMvc.perform(get("/check").param("apiKey", "check-test-key"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.allowed").value(true))
            .andExpect(jsonPath("$.currentCount").value(1))
            .andExpect(jsonPath("$.remaining").value(99))
            .andExpect(header().exists("X-RateLimit-Limit"))
            .andExpect(header().exists("X-RateLimit-Remaining"));
    }

    @Test
    void check_overLimit_returns429() throws Exception {
        CreateLimitRequest request = new CreateLimitRequest("limit-test-key", 2, 60);
        mockMvc.perform(post("/limits")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated());

        mockMvc.perform(get("/check").param("apiKey", "limit-test-key"))
            .andExpect(status().isOk());
        mockMvc.perform(get("/check").param("apiKey", "limit-test-key"))
            .andExpect(status().isOk());

        mockMvc.perform(get("/check").param("apiKey", "limit-test-key"))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.allowed").value(false))
            .andExpect(header().exists("Retry-After"));
    }

    @Test
    void usage_existingKey_returnsUsage() throws Exception {
        CreateLimitRequest request = new CreateLimitRequest("usage-test-key", 100, 60);
        mockMvc.perform(post("/limits")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated());

        mockMvc.perform(get("/check").param("apiKey", "usage-test-key"))
            .andExpect(status().isOk());

        mockMvc.perform(get("/usage").param("apiKey", "usage-test-key"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.apiKey").value("usage-test-key"))
            .andExpect(jsonPath("$.currentCount").value(1))
            .andExpect(jsonPath("$.limit").value(100));
    }

    @Test
    void deleteLimit_existing_returns204() throws Exception {
        CreateLimitRequest request = new CreateLimitRequest("delete-test-key", 100, 60);
        mockMvc.perform(post("/limits")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated());

        mockMvc.perform(delete("/limits/delete-test-key"))
            .andExpect(status().isNoContent());
    }

    @Test
    void deleteLimit_notFound_returns404() throws Exception {
        mockMvc.perform(delete("/limits/non-existent-key"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error", containsString("not found")));
    }

    @Test
    void listLimits_withData_returnsPaginatedResponse() throws Exception {
        mockMvc.perform(post("/limits")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateLimitRequest("list-key-1", 100, 60))))
            .andExpect(status().isCreated());

        mockMvc.perform(post("/limits")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateLimitRequest("list-key-2", 50, 30))))
            .andExpect(status().isCreated());

        mockMvc.perform(get("/limits").param("page", "0").param("size", "10"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content", hasSize(greaterThanOrEqualTo(2))))
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(10));
    }
}
```

- [ ] **Step 3: Run the integration tests**

Run: `./mvnw test -Dtest=RateLimitControllerIT -q`

Expected: Tests pass

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/example/demo/controller/ src/test/resources/
git commit -m "test: add integration tests with Testcontainers"
```

---

## Task 21: Final Verification

- [ ] **Step 1: Run all tests**

Run: `./mvnw test -q`

Expected: All tests pass

- [ ] **Step 2: Start services and verify manually**

Run:
```bash
docker-compose up -d
./mvnw spring-boot:run
```

- [ ] **Step 3: Test endpoints with curl**

```bash
# Create limit
curl -X POST http://localhost:8080/limits \
  -H "Content-Type: application/json" \
  -d '{"apiKey":"test-123","limit":5,"windowSeconds":60}'

# Check (run multiple times)
curl "http://localhost:8080/check?apiKey=test-123"

# Usage
curl "http://localhost:8080/usage?apiKey=test-123"

# List all
curl "http://localhost:8080/limits?page=0&size=10"

# Delete
curl -X DELETE http://localhost:8080/limits/test-123
```

- [ ] **Step 4: Commit all changes**

```bash
git add -A
git commit -m "feat: complete rate limiting service implementation"
```

---

## Summary

| Task | Description | Files |
|------|-------------|-------|
| 1 | Dependencies | pom.xml |
| 2 | Database schema | init.sql |
| 3 | Application config | application.yaml |
| 4 | JPA Entity | RateLimitConfig.java |
| 5 | Repository | RateLimitConfigRepository.java |
| 6 | DTOs | 6 DTO classes |
| 7 | Exceptions | 3 exception classes |
| 8 | Exception Handler | GlobalExceptionHandler.java |
| 9 | Redis config | RedisConfig.java, increment.lua |
| 10 | Caffeine config | CacheConfig.java |
| 11 | RocketMQ config | RocketMQConfig.java |
| 12 | RateLimiter | RateLimiter.java, IncrementResult.java |
| 13 | Event Publisher | RateLimitEvent.java, EventPublisher.java |
| 14 | Event Consumer | RateLimitEventConsumer.java |
| 15 | ConfigService | ConfigService.java |
| 16 | RateLimitService | RateLimitService.java |
| 17 | Controller | RateLimitController.java |
| 18 | Unit Tests | RateLimiterTest.java |
| 19 | Unit Tests | RateLimitServiceTest.java |
| 20 | Integration Tests | RateLimitControllerIT.java |
| 21 | Final Verification | Manual testing |
