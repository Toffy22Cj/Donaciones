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
     * {@code CONFIRMED → FUNDING_REJECTED}: escritura condicionada a {@code status = CONFIRMED}, con motivo y fecha en
     * la misma escritura (Enmienda 2 §4, C2). Devuelve si la transición se aplicó.
     */
    boolean markFundingRejectedIfConfirmed(String intentId, DonationIntent.FundingRejection rejection);

    /**
     * Marca de aplicación {@code fundsAppliedAt} (ADR-045 §2.5): escritura condicionada a {@code status = CONFIRMED} y
     * {@code fundsAppliedAt} ausente. Se llama dentro de la transacción del reclamo {@code APPLY_FUNDS}.
     */
    boolean markFundsApplied(String intentId, Instant appliedAt);

    /**
     * Intento fallido de aplicación (ADR-045 §2.3): escritura propia, posterior al rollback, condicionada a
     * {@code status = CONFIRMED} y {@code fundsAppliedAt} ausente. Incrementa {@code applicationAttempts} con
     * {@code $inc}, fija {@code firstApplicationAttemptAt} solo la primera vez, actualiza el último intento y la clase
     * del error, y pone la intención en cuarentena si {@code quarantine}. Devuelve el seguimiento resultante, o vacío
     * si la condición no se cumplió (ya aplicada, no confirmada o inexistente).
     */
    Optional<DonationIntent.ApplicationTracking> recordApplicationFailure(String intentId, String errorClass,
                                                                          Instant attemptedAt, boolean quarantine);

    /** Pone en cuarentena una intención {@code CONFIRMED} sin aplicar. Devuelve si cambió. */
    boolean quarantineApplication(String intentId);

    /**
     * Salida manual de la cuarentena (ADR-045 §2.3): condicionada a {@code status = CONFIRMED},
     * {@code fundsAppliedAt} ausente y {@code applicationQuarantined = true}; reinicia los contadores.
     * Devuelve si cambió.
     */
    boolean releaseApplicationQuarantine(String intentId);

    /**
     * Hasta {@code limit} intenciones recuperables (ADR-045 §2.4, §2.5): {@code CONFIRMED}, sin
     * {@code fundsAppliedAt}, fuera de cuarentena y sin reclamo {@code APPLY_FUNDS} (comprobación defensiva), excluidas
     * las de convocatorias {@code CLOSE_ON_TARGET + CLOSE} mientras R4 no exista (P9). Orden:
     * {@code applicationAttempts} ascendente, {@code lastApplicationAttemptAt} ascendente (las nunca intentadas
     * primero) e {@code intentId}.
     */
    List<DonationIntent> findConfirmedPendingApplication(int limit);

    /** Métrica (ADR-045 §2.6): intenciones {@code CONFIRMED} sin aplicar en cuarentena. */
    long countApplicationQuarantined();

    /** Métrica (ADR-045 §2.6): intenciones {@code CONFIRMED} sin aplicar excluidas por P9 ({@code CLOSE_ON_TARGET + CLOSE}). */
    long countPendingExcludedByCloseOnTargetClose();

    /** Métrica (ADR-045 §2.6): fecha de confirmación de la intención recuperable más antigua. */
    Optional<Instant> oldestPendingApplicationConfirmedAt();

    /** Enmienda 3 de ADR-037, D1: correlación del evento del proveedor. */
    Optional<DonationIntent> findByPaymentSessionId(String paymentSessionId);

    /**
     * Confirmación por pasarela con la misma barrera que la manual ({@code status = PENDING}), guardando el
     * {@code providerEventId} (Enmienda 3 de ADR-037, D1).
     */
    boolean confirmGatewayIfPending(String intentId, DonationIntent.Confirmation confirmation, String providerEventId);

    /** {@code PENDING → FAILED} (Enmienda 3 de ADR-037, D4), condicional. */
    boolean failIfPending(String intentId, String providerEventId, Instant failedAt);

    /**
     * Sustituye la credencial de consulta (DD-18 sustituida por Carlos, 2026-10-07): el hash anterior deja de valer.
     * Solo en intenciones que ya tienen credencial.
     */
    boolean rotateStatusToken(String intentId, String statusTokenHash, Instant statusTokenExpiresAt);

    /** Historial de un donante (ADR-048), como mucho {@code limit}. */
    List<DonationIntent> findByDonorRef(String donorRef, int limit);

    /** Intenciones de una convocatoria, como mucho {@code limit} (lectura del predictor, P3). */
    List<DonationIntent> findByCampaignRef(String campaignRef, int limit);
}
