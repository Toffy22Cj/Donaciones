package com.traceability.convocatoria.domain.exception;

/**
 * Asignar o designar un responsable en una convocatoria {@code CLOSED} (plan B6-a, Q-B6A-2 (ii);
 * `[DECISIÓN DELEGADA — pendiente de ratificar por Carlos]`, DD-05). Regla añadida a la Enmienda 1 de ADR-037 §4.
 */
public class ResponsibleAssignmentOnClosedCampaignException extends ConvocatoriaDomainException {

    public ResponsibleAssignmentOnClosedCampaignException(String message) {
        super(message);
    }
}
