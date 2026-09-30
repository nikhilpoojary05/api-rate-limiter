# Load test results

Measured with [`LoadTest.java`](LoadTest.java). Re-run with:

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

Measured 2026-10-01, after the single-parse JWT change described below.

| Scenario | Conc. | Req/s | p50 ms | p95 ms | p99 ms | Errors |
|---|---:|---:|---:|---:|---:|---:|
| Upstream alone, no gateway (baseline) | 64 | 41,434 | 1.15 | 3.13 | 7.77 | 0 |
| Through gateway, allowed | 16 | 4,535 | 3.35 | 5.62 | 7.21 | 0 |
| Through gateway, allowed | 64 | 4,750 | 13.31 | 20.38 | 23.91 | 0 |
| Through gateway, allowed | 128 | 4,743 | 26.75 | 40.43 | 46.63 | 0 |
| Through gateway, rejected (429) | 64 | 6,215 | 9.74 | 17.61 | 21.26 | 0 |

"Allowed" is the full path: JWT verification, the Lua rate-limit check in Redis, a traffic
event pushed to Redis, and the proxy to the upstream. "Rejected" stops after the limiter.

The gateway saturates at roughly **4,700–4,800 req/s**. Past concurrency 64 throughput is
flat and latency grows with queueing, the usual shape of a saturated service.

## Enforcement accuracy under concurrency

50 users, 300 requests each, shuffled together and fired at concurrency 200, so every
user's requests race both each other and everyone else's.

| Algorithm | Limit / user | Requests | Admitted per user | Users off-limit |
|---|---:|---:|---|---:|
| Sliding window | 100 | 15,000 | min 100, max 100 | **0 of 50** |
| Token bucket | 20 | 15,000 | min 20, max 20 | **0 of 50** |

Identical result before and after the change below, and in every run so far.

## Optimisation: parse the JWT once per request

**Finding.** The rejected path skips the upstream yet was only ~25% faster than the
allowed path, which pointed at work done on every request. A Java Flight Recorder
profile under load put **JWT parsing and signature verification at 22.9% of gateway CPU
samples**. `JwtAuthenticationFilter` called `validateToken` and then five `extract*`
methods, each re-parsing the token and re-checking its HMAC — six verifications per
request. A spy-based test confirmed the count: *wanted 1 time, but was 6 times*.

**Change.** `JwtUtils.verify()` parses and verifies once and returns every claim; the
gateway and admin filters use it. A test now pins one parse per authenticated request.

**Result.** Measured A/B — before and after jars alternated on the same upstream in the
same session, because this laptop drifts by ~10% between sessions:

| Concurrency 64 | Before (run 1, run 2) | After (run 1, run 2) | Change |
|---|---|---|---:|
| Allowed, req/s | 4,265 · 4,366 | 4,662 · 4,679 | **+8.2%** |
| Rejected, req/s | 5,589 · 5,732 | 6,225 · 6,208 | **+9.8%** |
| Allowed, p50 ms | 13.95 · 13.99 | 13.21 · 13.19 | −5.5% |
| Rejected, p50 ms | 10.77 · 10.51 | 9.63 · 9.65 | −9.4% |

JWT work fell from **22.9% to 9.9%** of gateway CPU samples in a repeat profile; the one
remaining parse is still an HMAC-SHA512 check and a JSON decode.

The throughput gain is smaller than the CPU removed, so the gateway's own CPU is not the
only limit on this machine. What the rest is has **not been measured**: an attempt to read
CPU utilisation during load disturbed the run it was measuring (throughput fell to 2,680)
and was discarded.

Note the post-change 4,750 req/s above is close to the pre-change 4,867 recorded in an
earlier session. That is session-to-session drift, not the change doing nothing — in the
same session the pre-change build measured 4,265–4,366. Compare builds only within an A/B.

## Logging

The gateway and demo service ship with `com.ratelimiter` at `DEBUG`. At concurrency 64
that cost ~7% throughput, but the larger problem is volume: **46 MB / 204,766 lines in
about 30 s** — on the order of 1.5 GB per minute at this load, with a user id on every
line. Production should run at `INFO`.

## Caveats

- **Closed-loop generator.** Each worker waits for a response before sending the next, so
  under saturation tail latency is under-reported (coordinated omission). Treat the p99s
  at concurrency 64 and 128 as optimistic.
- **Shared machine.** The generator competes with the system under test for CPU.
- **Drift.** Absolute numbers move by ~10% between sessions on this laptop; only
  same-session A/B comparisons are meaningful.
- **Synthetic tokens.** The tool signs its own JWTs with the gateway's secret, so the auth
  service's login path is not part of this measurement.
