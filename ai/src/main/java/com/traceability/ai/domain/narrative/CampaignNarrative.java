package com.traceability.ai.domain.narrative;

/**
 * Narrativa de una convocatoria tal como se puede publicar (plan B5 §4). Con {@code UNAVAILABLE} el contenido es
 * siempre {@link #UNAVAILABLE_TEXT}, nunca texto del LLM sin validar (condición 2 de Carlos).
 */
public record CampaignNarrative(Status status, String content, NarrativeSource source) {

    public enum Status { AVAILABLE, PENDING, UNAVAILABLE }

    public static final String UNAVAILABLE_TEXT = "Narrativa no disponible";

    public static CampaignNarrative available(String content) {
        return new CampaignNarrative(Status.AVAILABLE, content, NarrativeSource.LLM_GENERATED);
    }

    public static CampaignNarrative pending() {
        return new CampaignNarrative(Status.PENDING, null, null);
    }

    public static CampaignNarrative unavailable() {
        return new CampaignNarrative(Status.UNAVAILABLE, UNAVAILABLE_TEXT, null);
    }
}
