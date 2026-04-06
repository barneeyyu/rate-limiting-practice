# Data Models Specification

## MySQL

### Table: `rate_limit_config`

```sql
CREATE TABLE rate_limit_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    api_key VARCHAR(255) NOT NULL UNIQUE,
    request_limit INT NOT NULL,
    window_seconds INT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    INDEX idx_api_key (api_key)
);
```

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `id` | BIGINT | PK, AUTO_INCREMENT | Primary key |
| `api_key` | VARCHAR(255) | NOT NULL, UNIQUE | API key identifier |
| `request_limit` | INT | NOT NULL | Max requests allowed |
| `window_seconds` | INT | NOT NULL | Time window in seconds |
| `created_at` | DATETIME | NOT NULL, DEFAULT NOW | Record creation time |
| `updated_at` | DATETIME | NOT NULL, AUTO UPDATE | Last modification time |

---

## Redis

### Counter Key

| Attribute | Value |
|-----------|-------|
| Pattern | `ratelimit:count:{apiKey}` |
| Type | STRING (integer) |
| TTL | `windowSeconds` from config |
| Purpose | Track request count in current window |

### Config Cache Key

| Attribute | Value |
|-----------|-------|
| Pattern | `ratelimit:config:{apiKey}` |
| Type | HASH |
| TTL | 5 minutes |
| Fields | `limit`, `windowSeconds` |
| Purpose | Cache config to reduce MySQL reads |

---

## RocketMQ Event

### Message Format

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

### Event Types

| Type | Trigger | Details Fields |
|------|---------|----------------|
| `RATE_LIMIT_EXCEEDED` | Request blocked | `currentCount`, `limit`, `windowSeconds` |
| `CONFIG_CREATED` | New rule created | `limit`, `windowSeconds` |
| `CONFIG_DELETED` | Rule deleted | (none) |

### Topic

- Topic name: `rate-limit-events`
- Consumer group: `rate-limit-consumer-group`
