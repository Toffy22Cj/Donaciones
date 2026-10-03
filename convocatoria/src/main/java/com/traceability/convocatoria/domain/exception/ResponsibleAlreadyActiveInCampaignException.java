package com.traceability.convocatoria.domain.exception;

/**
 * La persona ya es responsable activa de la convocatoria: una sola asignación activa por persona y convocatoria (decisión humana del 2026-10-01, G3; protege el contador de ADR-037 §2.5).
 */
public class ResponsibleAlreadyActiveInCampaignException extends ConvocatoriaDomainException {

    public ResponsibleAlreadyActiveInCampaignException(String message) {
        super(message);
    }
}
