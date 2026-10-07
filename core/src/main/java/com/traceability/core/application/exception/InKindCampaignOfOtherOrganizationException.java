package com.traceability.core.application.exception;

import com.traceability.contracts.campaign.InKindEligibility;

/** La convocatoria es de otra organización. Mismo mensaje público que {@link InKindCampaignNotFoundException}. */
public class InKindCampaignOfOtherOrganizationException extends CampaignNotEligibleForInKindDonationException {

    public InKindCampaignOfOtherOrganizationException(String campaignRef) {
        super(CAMPAIGN_NOT_AVAILABLE_MESSAGE, campaignRef, InKindEligibility.OTHER_ORGANIZATION);
    }
}
