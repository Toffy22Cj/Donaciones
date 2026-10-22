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

    /**
     * Enmienda 5 de ADR-037, E5-2: {@code createdAt} (nulo en las anteriores a la enmienda), {@code confirmedAt} (solo
     * confirmadas o {@code FUNDING_REJECTED}), {@code organizationRef} (interno: {@code app} lo convierte en el nombre
     * y nunca lo devuelve), {@code paymentMethod} y {@code publicCode}, que es nulo salvo si la convocatoria es
     * {@code PUBLIC} al leer: el código de una {@code PRIVATE_LINK} nunca cruza este puerto (ADR-041 §2.7).
     */
    record DonationView(String intentId, String campaignTitle, long amount, String currency, String status,
                        String fundId, Instant fundsAppliedAt, Instant createdAt, Instant confirmedAt,
                        String organizationRef, String paymentMethod, String publicCode) {}
}
