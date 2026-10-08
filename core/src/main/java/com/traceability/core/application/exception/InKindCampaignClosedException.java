package com.traceability.core.application.exception;

import com.traceability.contracts.campaign.InKindEligibility;

/** La convocatoria no está {@code OPEN} en el momento del registro (Q5). */
public class InKindCampaignClosedException extends CampaignNotEligibleForInKindDonationException {

    public InKindCampaignClosedException(String campaignRef) {
        super("Campaign is not open", campaignRef, InKindEligibility.CAMPAIGN_CLOSED);
    }
}
