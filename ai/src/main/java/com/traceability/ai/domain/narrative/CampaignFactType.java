package com.traceability.ai.domain.narrative;

/**
 * Hechos que puede citar la narrativa de una convocatoria (plan B5 §3). Enum propio, separado de {@link FactType}, para
 * no cambiar el pipeline de la narrativa individual (propuesta D-IA §7).
 */
public enum CampaignFactType {
    CAMPAIGN_STATUS,
    TARGET_AMOUNT,
    CLEARED_AMOUNT,
    UNITS_DELIVERED,
    DISTINCT_RECIPIENTS
}
