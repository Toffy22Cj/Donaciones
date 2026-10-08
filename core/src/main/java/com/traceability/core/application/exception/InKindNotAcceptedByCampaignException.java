package com.traceability.core.application.exception;

import com.traceability.contracts.campaign.InKindEligibility;

/** La convocatoria no acepta {@code IN_KIND}. */
public class InKindNotAcceptedByCampaignException extends CampaignNotEligibleForInKindDonationException {

    public InKindNotAcceptedByCampaignException(String campaignRef) {
        super("Campaign does not accept in-kind donations", campaignRef, InKindEligibility.IN_KIND_NOT_ACCEPTED);
    }
}
