package com.traceability.convocatoria.infrastructure.persistence.mongo.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Set;

/**
 * Solicitud de cambio de configuración (Enmienda 4 de ADR-037, D2). Una sola {@code PENDING} por convocatoria:
 * índice único parcial {@code {campaignRef: 1} WHERE status = "PENDING"}.
 */
@Document(collection = ConfigurationChangeRequestDocument.COLLECTION)
@CompoundIndex(name = ConfigurationChangeRequestDocument.PENDING_INDEX, def = "{'campaignRef': 1}", unique = true,
        partialFilter = "{'status': 'PENDING'}")
public class ConfigurationChangeRequestDocument {

    public static final String COLLECTION = "configuration_change_requests";
    public static final String PENDING_INDEX = "uq_pending_configuration_change";

    @Id
    public String requestId;
    public String campaignRef;
    public String organizationRef;
    public long baseConfigurationVersion;
    public Set<String> acceptedDonationTypes;
    public Set<String> acceptedPaymentMethods;
    public String currency;
    public Long targetAmount;
    public String targetPolicy;
    public String onTargetReached;
    public String requestedBy;
    public Instant requestedAt;
    public String status;
    public String decidedBy;
    public Instant decidedAt;
    public Long resultingConfigurationVersion;
}
