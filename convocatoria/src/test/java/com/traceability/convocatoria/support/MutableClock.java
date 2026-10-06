package com.traceability.convocatoria.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Reloj de test controlable (vencimiento de {@code BANK_TRANSFER}). Por defecto sigue al reloj del sistema. */
public class MutableClock extends Clock {

    private volatile Instant fixed;

    public void set(Instant instant) {
        this.fixed = instant;
    }

    public void reset() {
        this.fixed = null;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        Instant current = fixed;
        return current != null ? current : Instant.now();
    }
}
