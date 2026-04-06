# Rate Limiting Service

A RESTful rate-limiting service built with Spring Boot, featuring multi-layer caching, Redis-based counters, and async event processing.

## Tech Stack

| Component | Technology |
|-----------|------------|
| Framework | Spring Boot 3.5 |
| Database | MySQL 8.0 |
| Cache | Redis 7 + Caffeine |
| Message Queue | RocketMQ 5.1 |
| Testing | JUnit 5 + Testcontainers |

## Quick Start

```bash
# 1. Start infrastructure
docker-compose up -d

# 2. Run the application
./mvnw spring-boot:run

# 3. Test the API
curl -X POST http://localhost:8080/limits \
  -H "Content-Type: application/json" \
  -d '{"apiKey":"test-key","limit":10,"windowSeconds":60}'

curl "http://localhost:8080/check?apiKey=test-key"
```

## API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/limits` | Create or update rate limit rule |
| GET | `/check?apiKey={key}` | Check rate limit (increments counter) |
| GET | `/usage?apiKey={key}` | Query current usage statistics |
| GET | `/limits?page=0&size=10` | List all rules (paginated) |
| DELETE | `/limits/{apiKey}` | Delete a rate limit rule |

### Response Examples

**POST /limits**
```json
// Request
{ "apiKey": "abc-123", "limit": 100, "windowSeconds": 60 }

// Response 201 Created
{ "apiKey": "abc-123", "limit": 100, "windowSeconds": 60 }
```

**GET /check** (allowed)
```
HTTP/1.1 200 OK
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 95
X-RateLimit-Reset: 1712404800

{ "allowed": true, "currentCount": 5, "limit": 100, "remaining": 95 }
```

**GET /check** (blocked)
```
HTTP/1.1 429 Too Many Requests
Retry-After: 45

{ "allowed": false, "currentCount": 101, "limit": 100, "remaining": 0, "retryAfterSeconds": 45 }
```

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                         Controller                               │
│                    RateLimitController                           │
└─────────────────────────┬───────────────────────────────────────┘
                          │
┌─────────────────────────▼───────────────────────────────────────┐
│                         Service                                  │
│      RateLimitService          ConfigService                     │
│      (check/usage)             (CRUD + caching)                  │
└─────────────────────────┬───────────────────────────────────────┘
                          │
┌─────────────────────────▼───────────────────────────────────────┐
│                      Infrastructure                              │
│   RateLimiter        Repository        EventPublisher            │
│   (Redis Lua)        (MySQL)           (RocketMQ)                │
└─────────────────────────────────────────────────────────────────┘
```

### Caching Flow

```
Request → Caffeine (L1) → Redis (L2) → MySQL (L3)
              ↓               ↓            ↓
           ~1μs           ~1ms         ~10ms
```

## Design Decisions & Trade-offs

### Performance & Architecture

| Decision | Choice | Trade-off | Value |
|----------|--------|-----------|-------|
| **Rate Limit Algorithm** | Fixed Window | Simpler than sliding window, slight boundary burst possible | Lowest memory overhead, minimal Redis computation |
| **Caching Strategy** | 3-Layer (Caffeine → Redis → MySQL) | Eventual consistency via MQ | Microsecond response time, maximized throughput |
| **Event Processing** | Async via RocketMQ | Logs may lag slightly | Zero latency impact on rate limit checks |

### Resilience & Security

| Decision | Choice | Trade-off | Value |
|----------|--------|-----------|-------|
| **Failure Mode** | Fail-Open | May allow excess requests during outage | Prevents rate limiter from being SPOF |
| **Missing API Key** | 401 Unauthorized | Less specific error info | Prevents user enumeration attacks |
| **Pagination** | Offset-based | Less efficient for large datasets | Intuitive random page access for admin UI |

### Why These Choices?

**Fixed Window over Sliding Window:**
- Redis `INCR + EXPIRE` is atomic and simple
- No need for sorted sets or complex Lua scripts
- Good enough for most rate limiting scenarios

**Fail-Open over Fail-Closed:**
- Rate limiting should protect, not block business
- When Redis is down, better to allow traffic than reject all
- Monitoring alerts on failure, not silent blocking

**401 over 404 for missing keys:**
- Attackers can't enumerate valid API keys
- Consistent with "unauthorized access" semantics
- Security by obscurity as defense-in-depth

## Project Structure

```
src/main/java/com/example/demo/
├── config/           # Redis, RocketMQ, Caffeine configuration
├── controller/       # REST endpoints
├── service/          # Business logic
├── ratelimiter/      # Redis counter operations (Lua script)
├── event/            # RocketMQ publisher & consumer
├── repository/       # JPA repository
├── entity/           # JPA entity
├── dto/              # Request/Response DTOs
└── exception/        # Custom exceptions & global handler
```

## Testing

```bash
# Run all tests
./mvnw test

# Run specific test class
./mvnw test -Dtest=RateLimitControllerIT
```

**Test Coverage:**
- Unit tests: RateLimiter, RateLimitService (Mockito)
- Integration tests: Full API flow with Testcontainers (MySQL + Redis)

## Environment

| Service | Port |
|---------|------|
| Application | 8080 |
| MySQL | 3306 |
| Redis | 6379 |
| RocketMQ NameServer | 9876 |
| RocketMQ Broker | 10911 |
| RocketMQ Console | 8088 |

**MySQL Credentials:** `taskuser` / `taskpass` / `taskdb`

## Tools

- **Postman Collection:** `postman/Rate-Limiting-Service.postman_collection.json`
- **RocketMQ Console:** http://localhost:8088
- **Database inspection:** See [HELP.md](HELP.md)

