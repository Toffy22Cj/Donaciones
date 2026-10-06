package com.traceability.convocatoria.application.port.out;

import com.traceability.convocatoria.domain.model.DonationIntent;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistencia de {@link DonationIntent} (ADR-037 §2.6; Enmienda §5; implementation_plan.md §9).
 */
public interface DonationIntentRepositoryPort {

    void insert(DonationIntent intent);

    Optional<DonationIntent> findById(String intentId);

    /** "Primera donación" en este corte (H1, implementation_plan.md §5): existe alguna intención de la convocatoria. */
    boolean existsByCampaignRef(String campaignRef);

    /**
     * Barrera {@code PENDING → CONFIRMED} (Enmienda §5.3): escritura condicionada a {@code status = PENDING} y,
     * si la intención tiene expiración, a {@code expiresAt > confirmedAt}. Devuelve si la transición se aplicó.
     */
    boolean confirmIfPending(String intentId, DonationIntent.Confirmation confirmation);

    /**
     * {@code CONFIRMED → FUNDING_REJECTED}: escritura condicionada a {@code status = CONFIRMED}. Devuelve si la
     * transición se aplicó (ADR-037 Enmienda 2 §4).
     */
    boolean markFundingRejectedIfConfirmed(String intentId);

    /**
     * Hasta {@code limit} intenciones {@code CONFIRMED} sin reclamo {@code APPLY_FUNDS} en el registro de comandos
     * procesados, ordenadas por {@code intentId}, excluidas las de convocatorias {@code CLOSE_ON_TARGET + CLOSE}
     * mientras R4 no exista (P9, ADR-037 Enmienda 2 §4).
     */
    List<DonationIntent> findConfirmedPendingApplication(int limit);
}
