package com.traceability.convocatoria.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Solicitud de cambio de configuración (Enmienda 1 de ADR-037, §3.2, N3; Enmienda 4, D2): la configuración completa
 * propuesta sobre una versión base. La aprueba otro {@code ADMINISTRATOR} o el {@code REPRESENTATIVE}, distinto del
 * solicitante; si la configuración avanzó, la aprobación falla. Inmutable: las transiciones son escrituras
 * condicionales del repositorio.
 */
public record ConfigurationChangeRequest(String requestId, String campaignRef, String organizationRef,
                                         long baseConfigurationVersion, ConvocatoriaConfiguration proposedConfiguration,
                                         String requestedBy, Instant requestedAt, ConfigurationChangeRequestStatus status,
                                         String decidedBy, Instant decidedAt, Long resultingConfigurationVersion) {

    public ConfigurationChangeRequest {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(campaignRef, "campaignRef");
        Objects.requireNonNull(organizationRef, "organizationRef");
        Objects.requireNonNull(proposedConfiguration, "proposedConfiguration");
        Objects.requireNonNull(requestedBy, "requestedBy");
        Objects.requireNonNull(requestedAt, "requestedAt");
        Objects.requireNonNull(status, "status");
    }

    public static ConfigurationChangeRequest pending(String requestId, Convocatoria convocatoria,
                                                     ConvocatoriaConfiguration proposed, String requestedBy, Instant now) {
        return new ConfigurationChangeRequest(requestId, convocatoria.getCampaignRef(), convocatoria.getOrganizationRef(),
                convocatoria.getConfigurationVersion(), proposed, requestedBy, now, ConfigurationChangeRequestStatus.PENDING,
                null, null, null);
    }
}
