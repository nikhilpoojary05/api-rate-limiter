# Load test results

Measured 2026-09-30 with [`LoadTest.java`](LoadTest.java). Re-run with:

```bash
JWT_SECRET=... GATEWAY_URL=http://localhost:8080 java loadtest/LoadTest.java
```

## Environment

Everything on one laptop: the load generator, gateway, upstream and Redis compete for
the same CPU, so these are **laptop numbers**, not a capacity figure for a real deployment.

| | |
|---|---|
| CPU | Intel Core i5-13420H, 8 cores / 12 threads |
| RAM | 23.7 GB |
| JVM | JDK 25.0.2 (project targets 21) |
| Stack | Spring Boot 3.5.16, Spring Cloud Gateway 4.3.5, Redis 7.2.5 |
| Logging | `INFO` (see the note on the shipped `DEBUG` default below) |
| Run length | 20 s per scenario after a 10 s JIT warm-up |

## Throughput and latency

| Scenario | Conc. | Req/s | p50 ms | p95 ms | p99 ms | Errors |
|---|---:|---:|---:|---:|---:|---:|
| Upstream alone, no gateway (baseline) | 64 | 43,296 | 1.11 | 2.87 | 8.65 | 0 |
| Through gateway, allowed | 16 | 4,279 | 3.47 | 6.33 | 8.54 | 0 |
| Through gateway, allowed | 64 | 4,867 | 12.90 | 20.41 | 24.53 | 0 |
| Through gateway, allowed | 128 | 4,757 | 26.60 | 40.39 | 47.41 | 0 |
| Through gateway, rejected (429) | 64 | 6,014 | 10.01 | 18.18 | 23.26 | 0 |

"Allowed" is the full path: JWT verification, the Lua rate-limit check in Redis, a traffic
event pushed to Redis, and the proxy to the upstream. "Rejected" stops after the limiter.

The gateway saturates at roughly **4,800 req/s**. Past concurrency 64 throughput is flat
and latency grows with queueing, the usual shape of a saturated service.

## Enforcement accuracy under concurrency

50 users, 300 requests each, shuffled together and fired at concurrency 200, so every
user's requests race both each other and everyone else's.

| Algorithm | Limit / user | Requests | Admitted per user | Users off-limit |
|---|---:|---:|---|---:|
| Sliding window | 100 | 15,000 | min 100, max 100 | **0 of 50** |
| Token bucket | 20 | 15,000 | min 20, max 20 | **0 of 50** |

## What limits throughput

The rejected path skips the upstream entirely yet is only ~25% faster than the allowed
path, so the cost is in work done on *every* request. A Java Flight Recorder profile of
the gateway under load at concurrency 64:

- **JWT parsing and signature verification: ~23% of gateway CPU samples.**
  `JwtAuthenticationFilter` calls `validateToken` and then five `extract*` methods, and
  each one re-parses the token and re-verifies the HMAC — **six full parses per request**.
  Parsing once and reading every claim from the result would remove about five-sixths of
  that work.

## Logging

The gateway and demo service ship with `com.ratelimiter` at `DEBUG`. At concurrency 64
that cost ~7% throughput (4,506 vs 4,867 req/s), but the larger problem is volume:
**46 MB / 204,766 lines in about 30 s** — on the order of 1.5 GB per minute at this load,
with a user id on every line. Production should run at `INFO`.

## Caveats

- **Closed-loop generator.** Each worker waits for a response before sending the next, so
  under saturation tail latency is under-reported (coordinated omission). Treat the p99s
  at concurrency 64 and 128 as optimistic.
- **Shared machine.** The generator competes with the system under test for CPU.
- **Synthetic tokens.** The tool signs its own JWTs with the gateway's secret, so the auth
  service's login path is not part of this measurement.
