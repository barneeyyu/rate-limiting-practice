# Design: Rate Limiting Service

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        Controller Layer                          │
│                    RateLimitController.java                      │
└─────────────────────────┬───────────────────────────────────────┘
                          │
┌─────────────────────────▼───────────────────────────────────────┐
│                        Service Layer                             │
│  ┌──────────────────┐  ┌──────────────────┐                     │
│  │ RateLimitService │  │  ConfigService   │                     │
│  │  (主業務邏輯)     │  │  (規則+快取管理)  │                     │
│  └────────┬─────────┘  └────────┬─────────┘                     │
└───────────┼─────────────────────┼───────────────────────────────┘
            │                     │
┌───────────▼─────────────────────▼───────────────────────────────┐
│                     Infrastructure Layer                         │
│  ┌──────────────┐  ┌──────────────┐  ┌────────────────┐         │
│  │  RateLimiter │  │  Repository  │  │ EventPublisher │         │
│  │  (Redis計數)  │  │   (MySQL)    │  │  (RocketMQ)    │         │
│  └──────────────┘  └──────────────┘  └────────────────┘         │
└─────────────────────────────────────────────────────────────────┘
```

## Key Design Decisions

| Decision | Choice | Reasoning |
|----------|--------|-----------|
| No rule behavior | Allow request | Explicit whitelist approach |
| Blocked response | 429 Too Many Requests | Standard HTTP semantics |
| Missing apiKey | 401 Unauthorized | Proper auth error code |
| Cache strategy | Caffeine → Redis → MySQL | Local cache reduces Redis calls |
| Cache penetration | Null object (30s TTL) | Prevents DB hammering from invalid keys |
| Counter implementation | Redis Lua script | Atomic INCR + EXPIRE |
| Event timing | Before exception throw | Guarantees event delivery |
| Pagination | Offset-based | Simple, meets requirements |

## Caching Flow

```
┌─────────────────────────────────────────────────────────────────┐
│                    getConfig(apiKey) Flow                        │
└─────────────────────────────────────────────────────────────────┘

     ┌──────────┐      miss      ┌──────────┐      miss      ┌──────────┐
     │ Caffeine │ ───────────▶  │  Redis   │ ───────────▶  │  MySQL   │
     │ (local)  │               │ (shared) │               │   (DB)   │
     └────┬─────┘               └────┬─────┘               └────┬─────┘
          │ hit                      │ hit                      │
          ▼                          ▼                          ▼
       return                  store Caffeine              store Redis(5m)
                                  return                   store Caffeine
                                                              return

     If MySQL miss → store null object in Caffeine(30s) → return null
```

## Redis Key Design

| Key Pattern | Type | TTL | Purpose |
|-------------|------|-----|---------|
| `ratelimit:count:{apiKey}` | STRING | windowSeconds | Request counter |
| `ratelimit:config:{apiKey}` | HASH | 5 min | Config cache |

## RocketMQ Events

| Event Type | Trigger | Purpose |
|------------|---------|---------|
| `RATE_LIMIT_EXCEEDED` | Request blocked (count > limit) | Audit/alerting |
| `CONFIG_CREATED` | POST /limits | Audit trail |
| `CONFIG_DELETED` | DELETE /limits/{apiKey} | Audit trail |
