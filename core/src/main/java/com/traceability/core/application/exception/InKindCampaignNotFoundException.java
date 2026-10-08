package com.traceability.core.application.exception;

import com.traceability.contracts.campaign.InKindEligibility;

/** La convocatoria no existe. Mismo mensaje público que {@link InKindCampaignOfOtherOrganizationException}. */
public class InKindCampaignNotFoundException extends CampaignNotEligibleForInKindDonationException {

    public InKindCampaignNotFoundException(String campaignRef) {
        super(CAMPAIGN_NOT_AVAILABLE_MESSAGE, campaignRef, InKindEligibility.CAMPAIGN_NOT_FOUND);
    }
}
