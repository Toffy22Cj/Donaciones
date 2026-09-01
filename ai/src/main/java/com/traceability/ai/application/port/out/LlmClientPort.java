package com.traceability.ai.application.port.out;

import com.traceability.ai.domain.exception.NarrativeGenerationTimeoutException;
import com.traceability.ai.domain.exception.NarrativeProviderException;
import com.traceability.ai.domain.narrative.LlmNarrativeResponse;
import com.traceability.contracts.AuditFactsDTO;

public interface LlmClientPort {
    LlmNarrativeResponse generateNarrative(AuditFactsDTO sanitizedFacts, String promptTemplateVersion)
        throws NarrativeGenerationTimeoutException, NarrativeProviderException;
}
