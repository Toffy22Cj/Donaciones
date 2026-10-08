package com.traceability.core.application.port.out;

import java.time.Instant;
import java.util.List;

/**
 * Fondos acreditados a una convocatoria según el event store: los eventos {@code FUNDS_CLEARED} (1.0 y 2.0) con ese
 * {@code campaignRef} en el payload, con su {@code occurredAt}. Es la base de lo recaudado en un instante pasado para
 * las estimaciones históricas (encargo 6, P3, S-10): nunca se usa el estado actual de las intenciones. Solo lectura.
 */
public interface CampaignClearedFundsPort {

    /** Un evento {@code FUNDS_CLEARED}: importe en unidades mínimas de la moneda. */
    record ClearedFund(String fundId, Instant occurredAt, long clearedAmount, String donorRef) {}

    /** Como mucho {@code limit} eventos, por orden de {@code occurredAt}. */
    List<ClearedFund> findClearedFundsByCampaign(String campaignRef, int limit);
}
