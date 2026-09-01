package com.traceability.ai.application.service;

import com.traceability.contracts.AuditFactsDTO;
import com.traceability.contracts.FinancialFlagDTO;
import com.traceability.contracts.TransitionFactDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class NarrativePromptSanitizer {

    private final int maxLength;

    public NarrativePromptSanitizer(@Value("${ai.narrative.sanitizer.max-length:100}") int maxLength) {
        this.maxLength = maxLength;
    }

    public AuditFactsDTO sanitize(AuditFactsDTO rawFacts) {
        if (rawFacts == null) return null;

        return new AuditFactsDTO(
            sanitizeString(rawFacts.fundId()),
            rawFacts.auditFactsSequence(),
            sanitizeTransitions(rawFacts.transitions()),
            sanitizeFinancials(rawFacts.financialFlags()),
            rawFacts.generatedAt()
        );
    }

    private List<TransitionFactDTO> sanitizeTransitions(List<TransitionFactDTO> transitions) {
        if (transitions == null) return null;
        return transitions.stream().map(t -> new TransitionFactDTO(
            sanitizeString(t.assetRef()),
            sanitizeString(t.fromStatus()),
            sanitizeString(t.toStatus()),
            t.occurredAtFrom(),
            t.occurredAtTo(),
            t.durationSeconds(),
            t.expectedMaximumSeconds(),
            t.anomaly()
        )).toList();
    }

    private List<FinancialFlagDTO> sanitizeFinancials(List<FinancialFlagDTO> financials) {
        if (financials == null) return null;
        return financials.stream().map(f -> new FinancialFlagDTO(
            sanitizeString(f.type()),
            sanitizeString(f.allocationId()),
            sanitizeString(f.refundId()),
            f.causedDeficit(),
            f.amount(),
            f.occurredAt()
        )).toList();
    }

    protected String sanitizeString(String input) {
        if (input == null) return null;

        // 1. Truncate
        String sanitized = input;
        if (sanitized.length() > maxLength) {
            sanitized = sanitized.substring(0, maxLength);
        }

        // 2. Remove control characters
        sanitized = sanitized.replaceAll("[\\x00-\\x1F\\x7F]", "");

        // 3. Escape delimiters for prompt injection (ADR-018)
        // Our SpringAiLlmAdapter uses """ as the strict delimiter.
        // We replace all double quotes with single quotes to ensure no """ sequence can be formed.
        sanitized = sanitized.replace("\"", "'");

        return sanitized;
    }
}
