# 🛡️ Real-Time API Rate Limiter & Security Gateway

A production-grade, pluggable API Gateway built with Spring Boot 3.2 that protects microservices with distributed rate limiting, JWT/OAuth2 security, multi-tenancy, and real-time analytics.

---

## Architecture

```
Client → API Gateway (8080) → Auth Service (8081)
                             → Admin Service (8082)
                             → Demo Service (8083)
                             → Redis (rate-limit counters, rule cache)
                             → PostgreSQL (tenants, users, traffic events)

Observability: Prometheus (9090) → Grafana (3001)
               Zipkin (9411)
Dashboard: React + Vite (3000)
```

## Module Structure

| Module | Port | Description |
|--------|------|-------------|
| `gateway-service` | 8080 | Spring Cloud Gateway — JWT filter, rate limiting filter |
| `auth-service` | 8081 | OAuth2/JWT issuance, user registration, RBAC |
| `admin-service` | 8082 | Rule management, tenant CRUD, live analytics SSE |
| `demo-service` | 8083 | Mock downstream service for testing |
| `common` | — | Shared DTOs, JwtUtils, exceptions |
| `dashboard` | 3000 | React + Vite admin dashboard |

---

## Quick Start

### Prerequisites
- Docker & Docker Compose
- Java 21 + Maven 3.9+ (for local development)
- Node.js 18+ (for dashboard)

### 1. Start Infrastructure

```bash
docker-compose up -d postgres redis zipkin prometheus grafana
```

### 2. Build & Start Services

```bash
# Build all modules
mvn clean package -DskipTests

# Start services (in separate terminals or background)
java -jar auth-service/target/auth-service-1.0.0.jar
java -jar gateway-service/target/gateway-service-1.0.0.jar
java -jar admin-service/target/admin-service-1.0.0.jar
java -jar demo-service/target/demo-service-1.0.0.jar
```

### 3. Start Dashboard

```bash
cd dashboard
npm install
npm run dev
```

### 4. Full Docker Compose (all services)

```bash
# Build JARs first
mvn clean package -DskipTests

# Start everything
docker-compose up --build
```

---

## Access Points

| Service | URL |
|---------|-----|
| **Admin Dashboard** | http://localhost:3000 |
| **API Gateway** | http://localhost:8080 |
| **Gateway Swagger** | http://localhost:8080/swagger-ui.html |
| **Auth Service Swagger** | http://localhost:8081/swagger-ui.html |
| **Admin Service Swagger** | http://localhost:8082/swagger-ui.html |
| **Prometheus** | http://localhost:9090 |
| **Grafana** | http://localhost:3001 (admin/admin) |
| **Zipkin** | http://localhost:9411 |

---

## Demo Credentials

| Username | Password | Tenant | Role |
|----------|----------|--------|------|
| `admin` | `Admin@123!` | `acme-corp` | ROLE_ADMIN |
| `john.doe` | `Test@123!` | `acme-corp` | ROLE_USER (TIER_A) |
| `jane.smith` | `Test@123!` | `beta-inc` | ROLE_USER (TIER_B) |

---

## API Usage

### 1. Login
```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"Admin@123!","tenantId":"acme-corp"}'
```

### 2. Call Demo Service (via Gateway)
```bash
curl -X GET http://localhost:8080/api/demo/ping \
  -H "Authorization: Bearer <access_token>"
```

### 3. Test Rate Limiting (Tier FREE = 10 req/min)
```bash
# Run this 11+ times — the 11th will return 429
for i in {1..15}; do
  curl -s -o /dev/null -w "%{http_code}\n" \
    http://localhost:8080/api/demo/ping \
    -H "Authorization: Bearer <free_user_token>"
done
```

### 4. Create Rate Limit Rule (Admin)
```bash
curl -X POST http://localhost:8080/api/admin/rules \
  -H "Authorization: Bearer <admin_token>" \
  -H "Content-Type: application/json" \
  -d '{
    "tenantId": "acme-corp",
    "tier": "TIER_A",
    "algorithm": "SLIDING_WINDOW",
    "requestLimit": 200,
    "windowMs": 60000,
    "burstCapacity": 300,
    "active": true
  }'
```

---

## Rate Limiting Algorithms

### Sliding Window (Default)
```
Redis Sorted Set: ZADD rl:sw:{tenant}:{user} <timestamp> <unique-id>
                  ZREMRANGEBYSCORE (clear expired)
                  ZCARD (count)
```
- **Pros**: Smooth, no spike at window boundary
- **Use case**: Standard API protection

### Token Bucket
```
Redis Hash: HMGET rl:tb:{key} tokens last_refill
            Refill = (elapsed_seconds × refill_rate)
            Decrement if tokens ≥ 1
```
- **Pros**: Allows controlled bursting for premium tiers
- **Use case**: Tier B/Enterprise with burst allowance

---

## Rate Limit Response Headers

When rate-limited:
```
HTTP/1.1 429 Too Many Requests
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 0
X-RateLimit-Reset: 1720000000000
Retry-After: 45
```

---

## Observability

### Prometheus Metrics (gateway-service)
```
gateway_requests_total{tenant, tier, status}
gateway_requests_blocked_total{tenant, tier}
gateway_request_duration_seconds{tenant, path}
```

### Distributed Tracing (Zipkin)
Every request through the gateway generates a Zipkin trace spanning:
`gateway-service → auth-service (JWT validation) → downstream service`

### Grafana Dashboard
Pre-built dashboard at `grafana/dashboards/gateway.json` with:
- Request rate by tenant
- Blocked requests over time  
- P99 latency
- Block rate %
- Latency heatmap

---

## Multi-Tenancy

Column-based isolation: every entity has a `tenant_id` column.

```sql
-- Rate limit keys are scoped per tenant+user
rl:sw:{tenant_id}:{user_id}
rl:tb:{tenant_id}:{user_id}
```

JWT claims carry `tenant_id` and `tier`, so the gateway extracts and forwards them as headers:
```
X-Tenant-Id: acme-corp
X-User-Tier: TIER_A
X-User-Roles: ROLE_ADMIN
```

---

## Security

- **JWT**: HMAC-SHA256, configurable secret via `JWT_SECRET` env var
- **Access Token**: 15 minutes TTL
- **Refresh Token**: 7 days TTL, stored in Redis for instant revocation
- **RBAC**: Spring Security with `ROLE_ADMIN`, `ROLE_USER`; gateway injects roles downstream
- **Rate limit bypass**: Public paths (`/api/auth/**`, `/actuator/**`) bypass JWT + rate limiting

---

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `JWT_SECRET` | (dev default) | JWT signing key — CHANGE IN PRODUCTION |
| `REDIS_HOST` | `localhost` | Redis hostname |
| `REDIS_PORT` | `6379` | Redis port |
| `ZIPKIN_URL` | `http://localhost:9411/api/v2/spans` | Zipkin endpoint |

---

## Running Tests

```bash
# Unit tests
mvn test

# Integration tests (requires Docker for Testcontainers)
mvn verify -P integration
```
