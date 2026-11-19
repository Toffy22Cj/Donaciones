package com.traceability.app.bootstrap.historic;

import com.traceability.core.domain.shared.EventTime;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * El {@link Clock} del perfil {@code demo-historico}: lee la misma fuente que {@code core} ({@link EventTime}). En el
 * hilo de la semilla, dentro de un bloque fechado, da el instante simulado; en cualquier otro hilo o fuera del bloque,
 * la hora real. Así {@code confirmedAt}, {@code createdAt} y el {@code occurredAt} de los eventos coinciden.
 */
final class EventTimeClock extends Clock {

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
        return EventTime.now();
    }
}
