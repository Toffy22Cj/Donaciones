package com.traceability.ai.application.port.out;

import com.traceability.ai.domain.narrative.LlmNarrativeResponse;
import com.traceability.contracts.AuditFactsDTO;

public interface GroundingValidator {
    boolean validate(LlmNarrativeResponse response, AuditFactsDTO facts);
}
