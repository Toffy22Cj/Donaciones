package com.traceability.core.application.port.out;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Liquidaciones de varios fondos con una consulta, para el recaudado por mes (ficha R-6, G-6; plan del Bloque B,
 * §2.2 y P-R6B-5). {@link EventStorePort} solo lee stream a stream. Solo lee el event store; no cambia ningún evento.
 */
public interface FundClearingEventsPort {

    /** Los {@code FUNDS_CLEARED} (1.0 y 2.0) de esos fondos, sin orden garantizado. */
    List<ClearingEvent> findFundsCleared(Collection<String> fundIds);

    /** La moneda de la génesis ({@code sequence = 1}) de cada fondo que la tenga. */
    Map<String, String> findGenesisCurrencies(Collection<String> fundIds);

    /** {@code currency} puede faltar en un payload 1.0 antiguo. */
    record ClearingEvent(String fundId, Instant occurredAt, long clearedAmount, String currency) {}
}
