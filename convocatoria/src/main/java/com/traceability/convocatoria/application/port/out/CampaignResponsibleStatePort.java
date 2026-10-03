package com.traceability.convocatoria.application.port.out;

import com.traceability.convocatoria.domain.model.CampaignResponsibleState;

import java.util.Optional;

/**
 * Contador {@code CampaignResponsibleState} (ADR-037 §2.5): escrituras condicionales atómicas, nunca read model.
 */
public interface CampaignResponsibleStatePort {

    /** {@code updateOne({campaignRef}, {$inc: +1}, upsert:true)}: inicialización e incremento atómicos. */
    void increment(String campaignRef);

    /** {@code updateOne({campaignRef, activeResponsibleCount: {$gt: 1}}, {$inc: -1})}. Devuelve si se aplicó. */
    boolean decrementIfMoreThanOne(String campaignRef);

    Optional<CampaignResponsibleState> find(String campaignRef);
}
