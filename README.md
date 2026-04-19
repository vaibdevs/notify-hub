# NotifyHub

![Java](https://img.shields.io/badge/Java-17-orange?style=flat-square)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.2-green?style=flat-square)
![Kafka](https://img.shields.io/badge/Apache_Kafka-231F20?style=flat-square&logo=apachekafka)
![Redis](https://img.shields.io/badge/Redis-DC382D?style=flat-square&logo=redis)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-15-blue?style=flat-square)
![Docker](https://img.shields.io/badge/Docker-Compose-2496ED?style=flat-square)
![Resilience4j](https://img.shields.io/badge/Resilience4j-Circuit_Breaker-yellow?style=flat-square)

A scalable multi-channel notification delivery platform built with Java Spring Boot. Client services like order management, auth, and payment systems call NotifyHub's API with a notification request — NotifyHub handles priority routing, rate limiting, retry logic, circuit breakers, and fans out delivery via Email, SMS, and Push.

---

## Table of Contents

- [What It Does](#what-it-does)
- [Tech Stack](#tech-stack)
- [Architecture](#architecture)
- [How It Works](#how-it-works)
- [Database Schema](#database-schema)
- [Getting Started](#getting-started)
- [API Reference](#api-reference)
- [Circuit Breaker States](#circuit-breaker-states)
- [Priority Queue Design](#priority-queue-design)
- [Project Structure](#project-structure)
- [Design Decisions](#design-decisions)
- [Running Tests](#running-tests)

---

## What It Does

Any backend service can call NotifyHub with a single request:

```json
POST /notify
{
  "tenantId": "swiggy",
  "recipientId": "user-001",
  "channel": ["EMAIL", "SMS"],
  "priority": "HIGH",
  "templateId": "order-confirmation",
  "templateData": {
    "userName": "Priya",
    "orderId": "ORD-9821"
  }
}
```

NotifyHub takes care of everything else:

- Checks if the tenant is within their rate limit
- Personalises the message using the template engine
- Routes to the correct Kafka priority topic
- Delivers via the right external provider
- Retries on failure with exponential backoff
- Opens circuit breaker if provider is consistently down
- Saves failed messages to Dead Letter Queue — nothing is lost
- Logs every delivery attempt with timestamp and status

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 17 |
| Framework | Spring Boot 3.2 |
| Message Broker | Apache Kafka |
| Circuit Breaker | Resilience4j |
| Rate Limiting | Redis |
| Database | PostgreSQL 15 |
| ORM | Spring Data JPA + Hibernate |
| Migrations | Flyway |
| Observability | Prometheus + Grafana |
| Containerisation | Docker + Docker Compose |
| Testing | Spring Boot Test + Testcontainers |

---

## Architecture

```
Client Services (Swiggy / Zomato / Infosys)
              │
              │ POST /notify
              ▼
┌─────────────────────────────────────────────────────────┐
│                      NotifyHub                          │
│                                                         │
│  Rate Limiter (Redis) → Template Engine → Kafka Router  │
│                                                         │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  │
│  │ notification │  │ notification │  │ notification │  │
│  │    .high     │  │   .medium    │  │    .low      │  │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘  │
│         │                 │                  │          │
│  ┌──────▼───────┐  ┌──────▼───────┐  ┌──────▼───────┐  │
│  │ Email Worker │  │  SMS Worker  │  │ Push Worker  │  │
│  │ CircuitBreak │  │ CircuitBreak │  │ CircuitBreak │  │
│  │ + Retry      │  │ + Retry      │  │ + Retry      │  │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘  │
└─────────┼─────────────────┼──────────────────┼──────────┘
          │                 │                  │
          ▼                 ▼                  ▼
      SendGrid           Twilio          FCM / APNs
      AWS SES            AWS SNS         (Google/Apple)

Failed after all retries → Dead Letter Queue → Admin replay
Every attempt → PostgreSQL delivery_logs → Prometheus → Grafana
```

---

## How It Works

### Happy Path
```
1. Client sends POST /notify
2. Rate limiter checks tenant quota in Redis
3. Template engine personalises the message
4. Routed to Kafka topic based on priority
5. Channel worker picks it up
6. Calls external provider (SendGrid / Twilio / FCM)
7. Provider delivers the message
8. Delivery logged as DELIVERED in PostgreSQL
```

### Failure Path
```
1. External provider returns error
2. Retry attempt 1 → wait 2 seconds
3. Retry attempt 2 → wait 4 seconds
4. Retry attempt 3 → wait 8 seconds
5. All retries exhausted → circuit breaker increments failure count
6. Failure count exceeds threshold → circuit breaker OPENS
7. Message saved to Dead Letter Queue
8. Admin sees it in dashboard → replays when provider recovers
```

### Circuit Breaker Recovery
```
CLOSED     → normal operation, requests flow through
    ↓ failures exceed threshold
OPEN       → fail fast, no calls to provider, messages go to DLQ
    ↓ wait period expires
HALF-OPEN  → send one test request
    ↓ success                    ↓ failure
CLOSED                         OPEN again
```

---

## Database Schema

### notifications
```sql
CREATE TABLE notifications (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    VARCHAR(100) NOT NULL,
    recipient_id VARCHAR(255) NOT NULL,
    channel      VARCHAR(20)  NOT NULL,
    priority     VARCHAR(20)  NOT NULL DEFAULT 'MEDIUM',
    template_id  VARCHAR(100) NOT NULL,
    content      TEXT         NOT NULL,
    status       VARCHAR(20)  NOT NULL DEFAULT 'QUEUED',
    created_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
    delivered_at TIMESTAMP,

    CONSTRAINT notifications_channel_check
        CHECK (channel IN ('EMAIL', 'SMS', 'PUSH')),
    CONSTRAINT notifications_priority_check
        CHECK (priority IN ('HIGH', 'MEDIUM', 'LOW')),
    CONSTRAINT notifications_status_check
        CHECK (status IN ('QUEUED', 'PROCESSING', 'DELIVERED', 'FAILED'))
);
```

### delivery_logs
```sql
CREATE TABLE delivery_logs (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    notification_id UUID         NOT NULL REFERENCES notifications(id),
    attempt_number  INTEGER      NOT NULL,
    status          VARCHAR(30)  NOT NULL,
    provider        VARCHAR(50)  NOT NULL,
    error_message   TEXT,
    attempted_at    TIMESTAMP    NOT NULL DEFAULT NOW(),

    CONSTRAINT delivery_logs_status_check
        CHECK (status IN ('DELIVERED', 'FAILED', 'CIRCUIT_OPEN', 'REPLAYING'))
);
```

### tenants
```sql
CREATE TABLE tenants (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       VARCHAR(100) NOT NULL UNIQUE,
    name            VARCHAR(255) NOT NULL,
    rate_limit      INTEGER      NOT NULL DEFAULT 5000,
    rate_window_sec INTEGER      NOT NULL DEFAULT 10,
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);
```

### templates
```sql
CREATE TABLE templates (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    template_id VARCHAR(100) NOT NULL UNIQUE,
    channel     VARCHAR(20)  NOT NULL,
    subject     VARCHAR(255),
    body        TEXT         NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);
```

---

## Getting Started

### Prerequisites

- Docker Desktop installed and running
- That is it. Everything else runs inside Docker.

### Run the full stack

```bash
# Clone the repo
git clone https://github.com/vaibdevs/notify-hub.git
cd notify-hub

# Start everything
docker compose up --build
```

This starts:
- Spring Boot app on port `8080`
- PostgreSQL on port `5432`
- Apache Kafka on port `9092`
- Redis on port `6379`
- Prometheus on port `9090`
- Grafana on port `3000`

### Verify it is running

```bash
curl http://localhost:8080/actuator/health
# Expected: {"status":"UP"}
```

### Other commands

```bash
# Run in background
docker compose up -d --build

# View app logs
docker compose logs -f app

# Stop everything
docker compose down

# Stop and wipe all data
docker compose down -v
```

### Run without Docker

```bash
# Start Kafka, Redis, PostgreSQL manually then:
mvn spring-boot:run
```

---

## API Reference

### Notifications

```
POST   /notify                      → submit notification request
GET    /notifications/{id}          → check delivery status + attempt log
GET    /notifications/{id}/attempts → full delivery attempt history
```

### Admin

```
GET    /admin/dlq                   → list all failed messages in Dead Letter Queue
POST   /admin/dlq/{id}/replay       → manually replay a failed notification
GET    /admin/stats                 → platform stats (delivery rate, DLQ size, p99)
GET    /admin/circuit-breakers      → current state of all circuit breakers
GET    /admin/tenants               → list all registered tenants
```

### Health + Metrics

```
GET    /actuator/health             → application health
GET    /actuator/metrics            → all metrics
GET    /actuator/prometheus         → Prometheus scrape endpoint
```

---

## Example Requests

**Send a HIGH priority email:**

```bash
curl -X POST http://localhost:8080/notify \
  -H "Content-Type: application/json" \
  -d '{
    "tenantId": "swiggy",
    "recipientId": "user-001",
    "channel": ["EMAIL"],
    "priority": "HIGH",
    "templateId": "order-confirmation",
    "templateData": {
      "userName": "Priya",
      "orderId": "ORD-9821"
    }
  }'
```

**Send to multiple channels:**

```bash
curl -X POST http://localhost:8080/notify \
  -H "Content-Type: application/json" \
  -d '{
    "tenantId": "zomato",
    "recipientId": "user-042",
    "channel": ["EMAIL", "SMS", "PUSH"],
    "priority": "HIGH",
    "templateId": "otp-verification",
    "templateData": {
      "otp": "847291",
      "expiryMinutes": "5"
    }
  }'
```

**Check delivery status:**

```bash
curl http://localhost:8080/notifications/abc-123-def
```

**View DLQ:**

```bash
curl http://localhost:8080/admin/dlq
```

**Replay a failed notification:**

```bash
curl -X POST http://localhost:8080/admin/dlq/abc-123/replay
```

**Check circuit breaker states:**

```bash
curl http://localhost:8080/admin/circuit-breakers
```

---

## Error Responses

```json
{
  "timestamp": "2024-03-14T10:30:00",
  "status": 429,
  "error": "Too Many Requests",
  "message": "Tenant swiggy has exceeded rate limit of 5000 per 10 seconds",
  "retryAfter": 8
}
```

| Status | When |
|---|---|
| `202` | Notification accepted and queued |
| `400` | Validation failed — missing fields or invalid channel |
| `404` | Notification or template not found |
| `429` | Tenant exceeded rate limit |
| `500` | Unexpected server error |

---

## Circuit Breaker States

```
GET /admin/circuit-breakers

{
  "email": {
    "state": "OPEN",
    "failureRate": "87.5%",
    "totalCalls": 120,
    "failedCalls": 105
  },
  "sms": {
    "state": "CLOSED",
    "failureRate": "2.1%",
    "totalCalls": 540,
    "failedCalls": 11
  },
  "push": {
    "state": "HALF_OPEN",
    "failureRate": "45.0%",
    "totalCalls": 80,
    "failedCalls": 36
  }
}
```

---

## Priority Queue Design

```
notification.high    → dedicated consumers, processed immediately
                       use case: OTPs, password resets, alerts

notification.medium  → standard processing alongside high
                       use case: order confirmations, account updates

notification.low     → background processing, yields to higher priority
                       use case: promotional emails, weekly digests
```

One producer, three topics, independent consumer groups per channel. Adding more consumers to a group scales throughput horizontally with zero code changes.

---

## Project Structure

```
src/
├── main/
│   ├── java/com/notifyhub/
│   │   ├── notification/       NotificationController, NotificationService
│   │   ├── email/              EmailWorker, EmailService
│   │   ├── sms/                SmsWorker, SmsService
│   │   ├── push/               PushWorker, PushService
│   │   ├── ratelimit/          RateLimiterService (Redis)
│   │   ├── template/           TemplateEngine, TemplateRepository
│   │   ├── dlq/                DLQService, DLQRepository
│   │   ├── admin/              AdminController, AdminService
│   │   ├── config/             KafkaConfig, RedisConfig, ResilienceConfig
│   │   ├── metrics/            PrometheusMetricsService
│   │   └── exception/          GlobalExceptionHandler, ErrorResponse
│   └── resources/
│       ├── db/migration/
│       │   ├── V1__create_notifications.sql
│       │   ├── V2__create_delivery_logs.sql
│       │   ├── V3__create_tenants.sql
│       │   └── V4__create_templates.sql
│       └── application.yml
├── test/
│   └── java/com/notifyhub/
│       ├── notification/       NotificationControllerTest
│       ├── email/              EmailWorkerTest
│       ├── ratelimit/          RateLimiterTest
│       └── circuitbreaker/     CircuitBreakerTest
├── Dockerfile
├── docker-compose.yml
└── grafana/
    └── dashboards/
        └── notifyhub.json
```

---

## Design Decisions

**Why Kafka instead of a simple queue?**
Kafka topics are durable and replayable. If a consumer crashes mid-processing, the message is not lost — Kafka replays it from the last committed offset. A simple in-memory queue loses everything on restart.

**Why three priority topics instead of one?**
A single topic means a LOW priority bulk campaign blocks HIGH priority OTPs. Separate topics with dedicated consumer groups ensure urgent messages are never stuck behind promotional ones.

**Why Resilience4j circuit breaker?**
Without a circuit breaker, a failed provider causes threads to pile up waiting for timeouts. Resilience4j fails fast when a provider is down, freeing resources immediately and preventing cascade failure across the platform.

**Why Redis for rate limiting?**
Redis INCR is atomic — no race condition between checking and incrementing the counter. This is the same pattern used in Project 1 (Distributed Rate Limiter) but applied per-tenant here.

**Why Dead Letter Queue instead of just logging failures?**
Logging tells you something failed. DLQ lets you fix it. Failed notifications sit in the DLQ and can be replayed with a single API call once the provider recovers — zero manual intervention.

**Why Prometheus + Grafana?**
You cannot operate a system you cannot see. Grafana dashboards show delivery rate, DLQ size, circuit breaker states, and p99 latency in real time — making it possible to catch problems before tenants report them.

**Why Flyway over `ddl-auto`?**
Same reason as every production system — versioned SQL files that run once in order. `ddl-auto` is set to `validate`. Flyway owns the schema entirely.

---

## Running Tests

```bash
# Run all tests
mvn test

# Run specific test
mvn test -Dtest=CircuitBreakerTest

# Run with coverage
mvn test jacoco:report
```

Tests use **Testcontainers** — real Kafka, Redis, and PostgreSQL containers spin up automatically for integration tests. No mocking of infrastructure.

---

## Observability

- **Prometheus** scrapes metrics at `/actuator/prometheus` every 15 seconds
- **Grafana** dashboard at `http://localhost:3000` (admin / admin)

Key metrics tracked:

| Metric | What it shows |
|---|---|
| `notifications_sent_total` | Total notifications accepted |
| `notifications_delivered_total` | Successfully delivered |
| `notifications_failed_total` | Failed after all retries |
| `dlq_size` | Current Dead Letter Queue depth |
| `delivery_latency_p99` | 99th percentile delivery time |
| `circuit_breaker_state` | OPEN / CLOSED / HALF-OPEN per channel |
| `rate_limit_rejections_total` | Requests rejected due to tenant quota |

---

## License

MIT License — free to use, modify, and distribute.

---

> Built to demonstrate production-grade resilience patterns — circuit breakers, priority queues, dead letter queues, and per-tenant rate limiting — the same patterns used in notification systems at Swiggy, Zomato, and Razorpay.
