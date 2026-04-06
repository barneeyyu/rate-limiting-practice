# API Endpoints Specification

## 1. POST /limits — Create/Update Rate Limit Rule

### Request

```json
{
  "apiKey": "abc-123",
  "limit": 100,
  "windowSeconds": 60
}
```

### Validation

| Field | Rule |
|-------|------|
| `apiKey` | Required, non-blank |
| `limit` | Required, 1 ~ 1,000,000 |
| `windowSeconds` | Required, 1 ~ 86400 (max 1 day) |

### Response

**201 Created**
```json
{
  "apiKey": "abc-123",
  "limit": 100,
  "windowSeconds": 60
}
```

**400 Bad Request** (validation failed)
```json
{
  "error": "Validation failed",
  "details": ["limit must be at least 1"]
}
```

---

## 2. GET /check — Check Rate Limit

### Request

```
GET /check?apiKey=abc-123
```

### Response

**200 OK** (allowed)

Headers:
```
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 95
X-RateLimit-Reset: 1712404800
```

Body:
```json
{
  "allowed": true,
  "currentCount": 5,
  "limit": 100,
  "remaining": 95
}
```

**429 Too Many Requests** (blocked)

Headers:
```
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 0
X-RateLimit-Reset: 1712404800
Retry-After: 45
```

Body:
```json
{
  "allowed": false,
  "currentCount": 101,
  "limit": 100,
  "remaining": 0,
  "retryAfterSeconds": 45
}
```

**401 Unauthorized** (no apiKey parameter)
```json
{
  "error": "API key is required"
}
```

**200 OK** (no rule configured — allow)
```json
{
  "allowed": true,
  "message": "No rate limit configured"
}
```

### Notes

- `retryAfterSeconds` is calculated from Redis key TTL
- `X-RateLimit-Reset` is Unix timestamp when window resets

---

## 3. GET /usage — Query Current Usage

### Request

```
GET /usage?apiKey=abc-123
```

### Response

**200 OK**
```json
{
  "apiKey": "abc-123",
  "currentCount": 50,
  "limit": 100,
  "remaining": 50,
  "ttlSeconds": 35
}
```

---

## 4. DELETE /limits/{apiKey} — Remove Rate Limit Rule

### Request

```
DELETE /limits/abc-123
```

### Response

**204 No Content** (success, no body)

**404 Not Found**
```json
{
  "error": "Rate limit config not found for apiKey: abc-123"
}
```

### Side Effects

- Removes config from MySQL
- Clears `ratelimit:config:{apiKey}` from Redis
- Clears `ratelimit:count:{apiKey}` from Redis
- Invalidates Caffeine cache
- Publishes `CONFIG_DELETED` event

---

## 5. GET /limits — List All Rate Limit Rules

### Request

```
GET /limits?page=0&size=10
```

| Parameter | Default | Description |
|-----------|---------|-------------|
| `page` | 0 | Page number (0-indexed) |
| `size` | 10 | Page size |

### Response

**200 OK**
```json
{
  "content": [
    {
      "apiKey": "abc-123",
      "limit": 100,
      "windowSeconds": 60
    },
    {
      "apiKey": "xyz-789",
      "limit": 50,
      "windowSeconds": 30
    }
  ],
  "page": 0,
  "size": 10,
  "totalElements": 2,
  "totalPages": 1
}
```
