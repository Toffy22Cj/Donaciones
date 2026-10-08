package com.traceability.convocatoria.application.query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Lecturas de {@code DonationIntent} para el donante (Enmienda 3 de ADR-037, D6; ADR-048). Ninguna vista lleva el
 * {@code donorRef}: el historial se pide por él, pero no lo devuelve.
 */
public interface DonationIntentReadPort {

    /** Vacío si la intención no existe o el token falta, no corresponde o caducó: hacia fuera, lo mismo (D6). */
    Optional<IntentStatusView> findForStatusToken(String intentId, String statusToken);

    /** Historial de un donante, como mucho {@code limit} intenciones. */
    List<DonationView> findByDonorRef(String donorRef, int limit);

    /** {@code fundId} y {@code fundsAppliedAt} son internos: {@code app} los usa para derivar el {@code trackingCode}. */
    record IntentStatusView(String intentId, String status, String fundId, Instant fundsAppliedAt) {}

    record DonationView(String intentId, String campaignTitle, long amount, String currency, String status,
                        String fundId, Instant fundsAppliedAt) {}
}
