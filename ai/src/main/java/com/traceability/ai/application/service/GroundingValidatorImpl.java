package com.traceability.ai.application.service;

import com.traceability.ai.application.port.out.GroundingValidator;
import com.traceability.ai.domain.narrative.CitedFact;
import com.traceability.ai.domain.narrative.LlmNarrativeResponse;
import com.traceability.contracts.AuditFactsDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.format.DateTimeParseException;

@Service
public class GroundingValidatorImpl implements GroundingValidator {

    private static final Logger log = LoggerFactory.getLogger(GroundingValidatorImpl.class);

    @Override
    public boolean validate(LlmNarrativeResponse response, AuditFactsDTO facts) {
        if (response == null || response.citedFacts() == null) {
            return false;
        }
        
        for (CitedFact citedFact : response.citedFacts()) {
            boolean matched = matchFact(citedFact, facts);
            if (!matched) {
                log.warn("Grounding validation failed. Cited fact not found or mismatched in raw facts: {}", citedFact);
                return false;
            }
        }
        
        return true;
    }

    private boolean matchFact(CitedFact citedFact, AuditFactsDTO facts) {
        if (citedFact.value() == null) {
            return false;
        }

        return switch (citedFact.type()) {
            case DATE -> matchDate(citedFact.value(), facts);
            case AMOUNT -> matchAmount(citedFact.value(), facts);
            default -> matchGeneric(citedFact.value(), facts);
        };
    }

    private boolean matchDate(String value, AuditFactsDTO facts) {
        try {
            Instant citedDate = Instant.parse(value);
            
            if (facts.generatedAt() != null && citedDate.equals(facts.generatedAt())) {
                return true;
            }
            
            if (facts.transitions() != null) {
                boolean transitionMatch = facts.transitions().stream()
                        .anyMatch(t -> (t.occurredAtFrom() != null && citedDate.equals(t.occurredAtFrom()))
                                || (t.occurredAtTo() != null && citedDate.equals(t.occurredAtTo())));
                if (transitionMatch) return true;
            }
            
            if (facts.financialFlags() != null) {
                return facts.financialFlags().stream()
                        .anyMatch(f -> f.occurredAt() != null && citedDate.equals(f.occurredAt()));
            }
            
            return false;
        } catch (DateTimeParseException e) {
            log.warn("Grounding validation error: Date cited fact is not a valid ISO-8601: {}", value);
            return false;
        }
    }

    private boolean matchAmount(String value, AuditFactsDTO facts) {
        try {
            long citedAmount = Long.parseLong(value);
            if (facts.financialFlags() != null) {
                return facts.financialFlags().stream()
                        .anyMatch(f -> citedAmount == f.amount());
            }
            return false;
        } catch (NumberFormatException e) {
            log.warn("Grounding validation error: Amount cited fact is not a valid integer/long number: {}", value);
            return false;
        }
    }

    private boolean matchGeneric(String value, AuditFactsDTO facts) {
        String normalizedCited = value.trim();
        
        if (facts.fundId() != null && normalizedCited.equalsIgnoreCase(facts.fundId().trim())) {
            return true;
        }
        
        if (facts.financialFlags() != null) {
            boolean flagMatch = facts.financialFlags().stream()
                    .anyMatch(f -> (f.type() != null && normalizedCited.equalsIgnoreCase(f.type().trim()))
                                || (f.allocationId() != null && normalizedCited.equalsIgnoreCase(f.allocationId().trim()))
                                || (f.refundId() != null && normalizedCited.equalsIgnoreCase(f.refundId().trim())));
            if (flagMatch) return true;
        }
        
        if (facts.transitions() != null) {
            return facts.transitions().stream()
                    .anyMatch(t -> (t.assetRef() != null && normalizedCited.equalsIgnoreCase(t.assetRef().trim()))
                                || (t.fromStatus() != null && normalizedCited.equalsIgnoreCase(t.fromStatus().trim()))
                                || (t.toStatus() != null && normalizedCited.equalsIgnoreCase(t.toStatus().trim())));
        }
        
        return false;
    }
}
