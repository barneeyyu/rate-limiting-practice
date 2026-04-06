# Help & Setup Guide

## Prerequisites

- Java 17+
- Docker & Docker Compose
- Maven (or use included `./mvnw`)

## Setup

### 1. Start Infrastructure

```bash
docker-compose up -d
```

Wait for all services to be healthy:
```bash
docker-compose ps
```

### 2. Run Application

```bash
./mvnw spring-boot:run
```

The application will start at http://localhost:8080

### 3. Run Tests

```bash
# All tests (requires Docker for Testcontainers)
./mvnw test

# Skip tests
./mvnw spring-boot:run -DskipTests
```

## Inspecting Data

### MySQL

```bash
# Connect to MySQL CLI
docker exec -it mysql mysql -utaskuser -ptaskpass taskdb

# Useful queries
SHOW TABLES;
SELECT * FROM rate_limit_config;
SELECT * FROM rate_limit_config WHERE api_key = 'test-key';
```

### Redis

```bash
# Connect to Redis CLI
docker exec -it redis redis-cli

# View all rate limit keys
KEYS ratelimit:*

# Check counter for a specific key
GET ratelimit:count:test-key

# Check TTL (seconds remaining)
TTL ratelimit:count:test-key

# View cached config
HGETALL ratelimit:config:test-key

# Clear all data (careful!)
FLUSHALL
```

### RocketMQ

**Web Console (Recommended):** http://localhost:8088

In the console you can:
- View topics → `rate-limit-events`
- Browse messages and their content
- Monitor consumer group status

```bash
# CLI: List topics
docker exec -it rocketmq-broker sh -c 'mqadmin topicList -n rocketmq-namesrv:9876'
```

## API Testing

### Using cURL

```bash
# Create a rate limit rule
curl -X POST http://localhost:8080/limits \
  -H "Content-Type: application/json" \
  -d '{"apiKey":"demo-key","limit":5,"windowSeconds":60}'

# Check rate limit (run multiple times to see counter increase)
curl -i "http://localhost:8080/check?apiKey=demo-key"

# Query usage
curl "http://localhost:8080/usage?apiKey=demo-key"

# List all rules
curl "http://localhost:8080/limits?page=0&size=10"

# Delete a rule
curl -X DELETE http://localhost:8080/limits/demo-key
```

### Using Postman

Import the collection: `postman/Rate-Limiting-Service.postman_collection.json`

## Troubleshooting

### Application won't start

**Error:** `Connection refused` to MySQL/Redis
```bash
# Check if containers are running
docker-compose ps

# Restart containers
docker-compose down && docker-compose up -d

# Check container logs
docker-compose logs mysql
docker-compose logs redis
```

**Error:** `Port already in use`
```bash
# Find and kill process using port 8080
lsof -i :8080
kill -9 <PID>
```

### Tests failing

**Testcontainers issues:**
```bash
# Make sure Docker is running
docker info

# Pull required images manually
docker pull mysql:8.0
docker pull redis:7
```

**Java version mismatch:**
```bash
# Check Java version (needs 17+)
java -version

# If using SDKMAN
sdk use java 17.0.x-tem
```

### Redis counter not incrementing

```bash
# Check if key exists
docker exec -it redis redis-cli EXISTS ratelimit:count:your-key

# Check Lua script execution
docker exec -it redis redis-cli MONITOR
# Then make a request and watch the output
```

### RocketMQ connection failed

```bash
# Check if RocketMQ is ready (may take 30-60s to start)
docker-compose logs rocketmq-broker

# Verify namesrv is accessible
nc -zv localhost 9876
```

## Clean Up

```bash
# Stop all containers
docker-compose down

# Stop and remove volumes (deletes all data)
docker-compose down -v

# Remove unused Docker resources
docker system prune -f
```

## Configuration

### application.yaml

Key configurations you might want to adjust:

```yaml
# Rate limit cache settings
rate-limit:
  cache:
    caffeine-max-size: 10000      # Max cached entries
    caffeine-ttl-seconds: 60       # Local cache TTL
    redis-config-ttl-seconds: 300  # Redis cache TTL
```

### Environment Variables

For production, override these via environment variables:

```bash
export SPRING_DATASOURCE_URL=jdbc:mysql://prod-db:3306/ratelimit
export SPRING_DATASOURCE_USERNAME=prod_user
export SPRING_DATASOURCE_PASSWORD=secure_password
export SPRING_DATA_REDIS_HOST=prod-redis
export ROCKETMQ_NAME_SERVER=prod-mq:9876
```
