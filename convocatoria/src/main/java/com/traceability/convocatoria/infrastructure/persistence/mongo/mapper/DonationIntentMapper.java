package com.traceability.convocatoria.infrastructure.persistence.mongo.mapper;

import com.traceability.convocatoria.domain.model.ConfirmationSource;
import com.traceability.convocatoria.domain.model.DonationIntent;
import com.traceability.convocatoria.domain.model.DonationIntentStatus;
import com.traceability.convocatoria.domain.model.PaymentMethod;
import com.traceability.convocatoria.infrastructure.persistence.mongo.document.DonationIntentDocument;

/**
 * Mapper manual {@code DonationIntent} ↔ documento (implementation_plan.md §4.3).
 */
public final class DonationIntentMapper {

    private DonationIntentMapper() {
    }

    public static DonationIntentDocument toDocument(DonationIntent i) {
        DonationIntentDocument d = new DonationIntentDocument();
        d.intentId = i.getIntentId();
        d.fundId = i.getFundId();
        d.organizationRef = i.getOrganizationRef();
        d.campaignRef = i.getCampaignRef();
        d.donorRef = i.getDonorRef();
        d.amount = i.getAmount();
        d.currency = i.getCurrency();
        d.paymentMethod = i.getPaymentMethod().name();
        d.confirmationSource = i.getConfirmationSource().name();
        d.configurationVersion = i.getConfigurationVersion();
        d.paymentSessionId = i.getPaymentSessionId();
        d.providerEventId = i.getProviderEventId();
        d.expiresAt = i.getExpiresAt();
        d.status = i.getStatus().name();
        DonationIntent.Confirmation c = i.getConfirmation();
        if (c != null) {
            d.confirmedBy = c.confirmedBy();
            d.confirmedAt = c.confirmedAt();
            d.confirmationPaymentMethod = c.paymentMethod().name();
            d.confirmationReference = c.reference();
        }
        DonationIntent.ApplicationTracking t = i.getApplicationTracking();
        if (!DonationIntent.ApplicationTracking.NONE.equals(t)) {
            d.fundsAppliedAt = t.fundsAppliedAt();
            d.applicationAttempts = t.attempts();
            d.firstApplicationAttemptAt = t.firstAttemptAt();
            d.lastApplicationAttemptAt = t.lastAttemptAt();
            d.lastApplicationError = t.lastError();
            d.applicationQuarantined = t.quarantined();
        }
        DonationIntent.FundingRejection r = i.getFundingRejection();
        if (r != null) {
            d.fundingRejectedAt = r.rejectedAt();
            d.fundingRejectionReason = r.reason();
        }
        return d;
    }

    public static DonationIntent toDomain(DonationIntentDocument d) {
        DonationIntent.Confirmation confirmation = d.confirmedAt == null ? null : new DonationIntent.Confirmation(
                d.confirmedBy, d.confirmedAt, PaymentMethod.valueOf(d.confirmationPaymentMethod), d.confirmationReference);
        return DonationIntent.reconstitute(d.intentId, d.fundId, d.organizationRef, d.campaignRef, d.donorRef,
                d.amount, d.currency, PaymentMethod.valueOf(d.paymentMethod),
                ConfirmationSource.valueOf(d.confirmationSource), d.configurationVersion, d.paymentSessionId,
                d.providerEventId, d.expiresAt, DonationIntentStatus.valueOf(d.status), confirmation,
                new DonationIntent.ApplicationTracking(d.fundsAppliedAt,
                        d.applicationAttempts == null ? 0 : d.applicationAttempts,
                        d.firstApplicationAttemptAt, d.lastApplicationAttemptAt, d.lastApplicationError,
                        Boolean.TRUE.equals(d.applicationQuarantined)),
                d.fundingRejectedAt == null ? null
                        : new DonationIntent.FundingRejection(d.fundingRejectedAt, d.fundingRejectionReason));
    }
}
