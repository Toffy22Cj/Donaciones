package com.traceability.convocatoria.domain.exception;

/**
 * El actor no pertenece a la organización de la convocatoria (implementation_plan.md §6).
 */
public class ActorNotInCampaignOrganizationException extends ConvocatoriaDomainException {

    public ActorNotInCampaignOrganizationException(String message) {
        super(message);
    }
}
