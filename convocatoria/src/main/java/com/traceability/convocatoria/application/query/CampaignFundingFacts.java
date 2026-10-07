package com.traceability.convocatoria.application.query;

import java.time.Instant;

/**
 * Hechos de financiación de una convocatoria para su narrativa (ADR-040; plan B5, DD-35). Interno: lo consume el
 * productor de {@code app}; nunca sale tal cual en una respuesta pública ({@code campaignRef} y {@code organizationRef}
 * son internos). Sin título ni descripción: no llegan al LLM.
 *
 * @param currency      solo con {@code MONETARY}
 * @param targetAmount  solo con {@code MONETARY}
 * @param targetPolicy  solo con {@code MONETARY}
 * @param clearedAmount solo con {@code MONETARY}
 * @param readAt        instante de esta lectura (DD-35: lecturas independientes)
 */
public record CampaignFundingFacts(String campaignRef, String organizationRef, String status, String currency,
                                   Long targetAmount, String targetPolicy, Long clearedAmount, Instant readAt) {}
