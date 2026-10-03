package com.traceability.convocatoria.infrastructure.persistence.mongo.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Documento de {@code CampaignFundingLedger} (ADR-037 §2.2; N2). {@code _id = campaignRef} (único, 1:1 solo con
 * {@code MONETARY}).
 */
@Document(collection = CampaignFundingLedgerDocument.COLLECTION)
public class CampaignFundingLedgerDocument {

    public static final String COLLECTION = "campaign_funding_ledgers";

    @Id
    public String campaignRef;
    public String currency;
    public long targetAmount;
    public String targetPolicy;
    public String onTargetReached;
    public long clearedAmount;
}
