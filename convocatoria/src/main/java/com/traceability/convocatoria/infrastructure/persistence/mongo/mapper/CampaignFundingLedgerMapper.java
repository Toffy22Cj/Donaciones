package com.traceability.convocatoria.infrastructure.persistence.mongo.mapper;

import com.traceability.convocatoria.domain.model.CampaignFundingLedger;
import com.traceability.convocatoria.domain.model.OnTargetReached;
import com.traceability.convocatoria.domain.model.TargetPolicy;
import com.traceability.convocatoria.infrastructure.persistence.mongo.document.CampaignFundingLedgerDocument;

/**
 * Mapper manual {@code CampaignFundingLedger} ↔ documento (implementation_plan.md §4.3).
 */
public final class CampaignFundingLedgerMapper {

    private CampaignFundingLedgerMapper() {
    }

    public static CampaignFundingLedgerDocument toDocument(CampaignFundingLedger l) {
        CampaignFundingLedgerDocument d = new CampaignFundingLedgerDocument();
        d.campaignRef = l.campaignRef();
        d.currency = l.currency();
        d.targetAmount = l.targetAmount();
        d.targetPolicy = l.targetPolicy().name();
        d.onTargetReached = l.onTargetReached() == null ? null : l.onTargetReached().name();
        d.clearedAmount = l.clearedAmount();
        return d;
    }

    public static CampaignFundingLedger toDomain(CampaignFundingLedgerDocument d) {
        return new CampaignFundingLedger(d.campaignRef, d.currency, d.targetAmount, TargetPolicy.valueOf(d.targetPolicy),
                d.onTargetReached == null ? null : OnTargetReached.valueOf(d.onTargetReached), d.clearedAmount);
    }
}
