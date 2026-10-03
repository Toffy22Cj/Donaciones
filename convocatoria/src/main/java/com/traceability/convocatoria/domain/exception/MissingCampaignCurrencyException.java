package com.traceability.convocatoria.domain.exception;

/**
 * `currency` obligatoria si `MONETARY ∈ acceptedDonationTypes` (R1; implementation_plan.md §3.1).
 */
public class MissingCampaignCurrencyException extends ConvocatoriaDomainException {

    public MissingCampaignCurrencyException(String message) {
        super(message);
    }
}
