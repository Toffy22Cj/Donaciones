package com.traceability.core.application.port.out;

import java.time.Instant;
import java.util.Optional;

/**
 * Fecha del pago de una donación (ficha R-6, G-2; D-R6-2; plan del Bloque B, P-R6B-5): el {@code occurredAt} del
 * primer y del último {@code FUNDS_CLEARED} (1.0 o 2.0) del stream del fondo, por orden de {@code sequence}, nunca por
 * fecha. Se lee del event store al consultar, sin tocar la proyección (C-2).
 */
public interface FundClearingDatesReadPort {

    /** Vacío si el fondo no tiene ningún {@code FUNDS_CLEARED} o no existe. */
    Optional<ClearingDates> findClearingDates(String fundId);

    record ClearingDates(Instant firstClearedAt, Instant lastClearedAt) {}
}
