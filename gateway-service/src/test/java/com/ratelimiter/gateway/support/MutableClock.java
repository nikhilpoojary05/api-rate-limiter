package com.ratelimiter.gateway.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A clock the test moves by hand. Lets limiter tests put many requests in literally the
 * same millisecond, and advance time without sleeping — so they are deterministic rather
 * than dependent on scheduler timing.
 */
public final class MutableClock extends Clock {

    private final AtomicLong millis;

    public MutableClock(long startMillis) {
        this.millis = new AtomicLong(startMillis);
    }

    public static MutableClock startingNow() {
        return new MutableClock(System.currentTimeMillis());
    }

    public void advance(Duration by) {
        millis.addAndGet(by.toMillis());
    }

    @Override
    public long millis() {
        return millis.get();
    }

    @Override
    public Instant instant() {
        return Instant.ofEpochMilli(millis.get());
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
