package com.traceability.convocatoria.domain.exception;

/**
 * Convocatoria o ledger inexistente (ADR-037 §2.2, §2.6; nombre ya fijado en ADR-037/ADR-041).
 */
public class CampaignNotFoundException extends ConvocatoriaDomainException {

    public CampaignNotFoundException(String message) {
        super(message);
    }
}
