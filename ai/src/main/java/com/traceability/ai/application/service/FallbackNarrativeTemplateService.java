package com.traceability.ai.application.service;

import com.traceability.ai.application.config.AiNarrativeProperties;
import com.traceability.ai.domain.narrative.DonorReportDTO;
import com.traceability.ai.domain.narrative.NarrativeSource;
import com.traceability.contracts.AuditFactsDTO;
import org.springframework.stereotype.Service;
import java.time.Instant;

@Service
public class FallbackNarrativeTemplateService {

    /** El fallback no lo produce ningún modelo: se guarda con este identificador en lugar del modelo configurado. */
    public static final String MODEL_IDENTIFIER = "FALLBACK";


    private final AiNarrativeProperties properties;

    public FallbackNarrativeTemplateService(AiNarrativeProperties properties) {
        this.properties = properties;
    }

    public DonorReportDTO createFallback(AuditFactsDTO facts, String sourceFactsHash) {
        String template = "The donation for fund %s has been processed up to sequence %d. " +
                          "It has %d transition(s) and %d flag(s) registered. " +
                          "Detailed AI narrative is temporarily unavailable.";
        
        String text = String.format(template, facts.fundId(), facts.auditFactsSequence(), 
                                    facts.transitions().size(), facts.financialFlags().size());
        
        Instant now = Instant.now();
        Instant nextRetry = now.plus(properties.getFallbackRetryInterval());

        return new DonorReportDTO(
            text,
            NarrativeSource.FALLBACK_TEMPLATE,
            MODEL_IDENTIFIER,
            properties.getPromptTemplateVersion(),
            sourceFactsHash,
            facts.auditFactsSequence(),
            now,
            nextRetry
        );
    }
}
