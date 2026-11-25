package com.traceability.api.web.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

/** Reloj de prueba que se avanza a mano. */
final class MutableClock extends Clock {
    private final AtomicLong millis = new AtomicLong(1_700_000_000_000L);

    void advance(Duration d) {
        millis.addAndGet(d.toMillis());
    }

    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public long millis() { return millis.get(); }
    @Override public Instant instant() { return Instant.ofEpochMilli(millis.get()); }
}
