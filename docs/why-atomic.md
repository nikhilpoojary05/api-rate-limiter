# Why the limiter is a Lua script

The gateway's sliding-window limiter does three things per request: drop entries older
than the window, count what is left, and record the request if the count is under the
limit. It does them inside one Lua script, which Redis runs atomically. This is the
experiment that shows why that matters.

## The race

Run the same three steps as separate Redis commands and two concurrent requests from one
user can interleave like this, one entry short of the limit:

| Request A | Request B | Entries |
|---|---|---:|
| `ZCARD` → 99, under the limit | | 99 |
| | `ZCARD` → 99, under the limit | 99 |
| `ZADD` | | 100 |
| | `ZADD` | **101** |

Both read "under the limit" before either wrote. The more requests in flight for one key,
the more of them read the same stale count.

## The experiment

[`loadtest/NaiveVsAtomic.java`](../loadtest/NaiveVsAtomic.java) runs the gateway's exact
algorithm both ways against the same Redis: as separate commands, and as the gateway's own
[`sliding_window.lua`](../gateway-service/src/main/resources/scripts/sliding_window.lua).
Limit 100 per user per minute, 200 concurrent workers, three runs each.

**50 users, 300 requests each**, a few of each user's requests racing at once:

| Variant | Admitted per user (min / max) | Users over the limit | Requests over the limit |
|---|---|---:|---:|
| Separate commands | 100 / 104–106 | 36–37 of 50 | 60–75 per run |
| One Lua script | 100 / 100 | **0 of 50** | **0** |

**One user, 2,000 requests**, all 200 workers racing on one key, as a single client or
attacker bursting would:

| Variant | Admitted, three runs | Over the limit |
|---|---|---:|
| Separate commands | 117, 118, 157 | up to **57%** |
| One Lua script | 100, 100, 100 | **0** |

Measured 2026-10-09 on one laptop, Redis 7 in Docker.

## Reading the numbers

- **The non-atomic version fails exactly when a limiter matters**: under a burst on one
  key. It is also unpredictable, 17 to 57 extra requests across three identical runs,
  because the damage depends on how the requests happen to interleave.
- **These are best-case numbers for the non-atomic version.** Redis ran on the same
  machine, so each round trip took well under a millisecond and the gap between reading
  the count and writing the entry was tiny. Over a real network, round trips take longer,
  the gap widens, and more requests slip through it.
- **Atomicity is what makes it exact, not Redis being fast.** The script runs the same
  commands; Redis just runs them with nothing else in between.

## Reproduce

```bash
REDIS_PORT=6379 REDIS_PASSWORD=... java loadtest/NaiveVsAtomic.java
```

It touches only its own `exp:*` keys and deletes them. With the Docker stack, use the
`REDIS_HOST_PORT` and `REDIS_PASSWORD` from `.env`.
