package com.traceability.convocatoria.application.port.out;

import com.traceability.convocatoria.domain.model.CampaignFundingLedger;

import java.util.Optional;

/**
 * Persistencia de {@link CampaignFundingLedger} (ADR-037 §2.2; Enmienda §3.1 N2, §3.3; implementation_plan.md §8).
 */
public interface CampaignFundingLedgerRepositoryPort {

    void insert(CampaignFundingLedger ledger);

    Optional<CampaignFundingLedger> findByCampaignRef(String campaignRef);

    /**
     * Retira el ledger cuando una edición directa quita {@code MONETARY} (N2: el ledger existe solo si la convocatoria
     * acepta {@code MONETARY}; decisión humana del 2026-10-01, G1, reportada en implementation_plan.md §16).
     */
    void deleteByCampaignRef(String campaignRef);

    /** {@code updateOne({campaignRef}, {$inc})}: sin condición de capacidad. Devuelve si hubo coincidencia. */
    boolean incrementUnconditionally(String campaignRef, long amount);

    /**
     * {@code updateOne({campaignRef, clearedAmount ≤ targetAmount − amount}, {$inc})} (ADR-037 §2.2).
     * {@code status} de la convocatoria no forma parte del filtro (D1). Devuelve si hubo coincidencia.
     */
    boolean incrementWithinTarget(String campaignRef, long amount, long targetAmount);
}
