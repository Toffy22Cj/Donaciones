package com.traceability.ai.domain.narrative;

import java.util.List;

/** Respuesta estructurada del LLM para la narrativa de una convocatoria: el texto y los hechos que cita. */
public record CampaignLlmNarrativeResponse(String narrativeText, List<CampaignCitedFact> citedFacts) {}
