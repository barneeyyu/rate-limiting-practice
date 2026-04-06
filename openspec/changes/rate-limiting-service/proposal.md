# Rate Limiting Service

## Summary

Build a RESTful API rate-limiting service that tracks usage by API key and blocks requests exceeding configured thresholds.

## Motivation

This is a backend engineer technical assignment requiring integration of Spring Boot, MySQL, Redis, and RocketMQ.

## Scope

### In Scope

- 5 REST API endpoints for rate limit management
- Redis-based sliding window counter
- MySQL persistence for rate limit rules
- Multi-layer caching (Caffeine + Redis)
- RocketMQ event publishing for auditing
- Unit tests + Integration tests (Testcontainers)

### Out of Scope

- User authentication/authorization system
- Admin UI
- Metrics/monitoring dashboard
- Distributed rate limiting across multiple instances (single instance assumed)

## Success Criteria

- All 5 endpoints work correctly per spec
- Rate limiting accurately enforces configured limits
- Standard HTTP headers (X-RateLimit-*) returned
- Events published to RocketMQ on block/config changes
- Test coverage for core logic
