package com.traceability.ai.application.port.out;

import com.traceability.ai.domain.exception.NarrativeGenerationTimeoutException;
import com.traceability.ai.domain.exception.NarrativeProviderException;
import com.traceability.ai.domain.narrative.CampaignLlmNarrativeResponse;
import com.traceability.ai.domain.narrative.CampaignNarrativeFacts;

/** LLM de la narrativa de convocatoria (plan B5). Solo recibe {@link CampaignNarrativeFacts}. */
public interface CampaignLlmClientPort {
    CampaignLlmNarrativeResponse generateCampaignNarrative(CampaignNarrativeFacts facts, String promptTemplateVersion)
            throws NarrativeGenerationTimeoutException, NarrativeProviderException;
}
