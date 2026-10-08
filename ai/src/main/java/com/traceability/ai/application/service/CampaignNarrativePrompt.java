package com.traceability.ai.application.service;

import com.traceability.ai.domain.narrative.CampaignNarrativeFacts;

/**
 * Prompt de la narrativa de convocatoria (plan B5 §2). Se construye solo con {@link CampaignNarrativeFacts}: ningún
 * texto libre de la convocatoria (título, descripción) ni identificador interno puede llegar al modelo.
 */
public final class CampaignNarrativePrompt {

    private CampaignNarrativePrompt() {}

    public static String render(CampaignNarrativeFacts facts, String outputFormat) {
        return "Escribe en español, en dos o tres frases, un resumen público del avance de una convocatoria de donaciones."
                + " Usa solo los hechos de abajo y cita cada hecho que menciones en citedFacts con su valor exacto."
                + " No escribas ninguna cifra que no esté en los hechos citados."
                + " Habla de \"receptores distintos\"; nunca de familias ni hogares."
                + "\n\nDevuelve un JSON que cumpla este esquema:\n" + outputFormat
                + "\n\nHechos:\n"
                + "CAMPAIGN_STATUS=" + facts.status() + "\n"
                + "CURRENCY=" + facts.currency() + "\n"
                + "TARGET_AMOUNT=" + value(facts.targetAmount()) + "\n"
                + "TARGET_POLICY=" + facts.targetPolicy() + "\n"
                + "CLEARED_AMOUNT=" + value(facts.clearedAmount()) + "\n"
                + "UNITS_DELIVERED=" + CampaignNarrativeFacts.plain(facts.unitsDelivered()) + "\n"
                + "DISTINCT_RECIPIENTS=" + facts.distinctRecipients() + "\n";
    }

    private static String value(java.math.BigDecimal amount) {
        return amount == null ? "null" : CampaignNarrativeFacts.plain(amount);
    }
}
