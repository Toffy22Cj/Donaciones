package com.traceability.convocatoria.infrastructure.persistence.mongo.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Documento de {@code CampaignResponsibleState} (ADR-037 §2.5). {@code _id = campaignRef} (único).
 */
@Document(collection = CampaignResponsibleStateDocument.COLLECTION)
public class CampaignResponsibleStateDocument {

    public static final String COLLECTION = "campaign_responsible_state";

    @Id
    public String campaignRef;
    public long activeResponsibleCount;
}
