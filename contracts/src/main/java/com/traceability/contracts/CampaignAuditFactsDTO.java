package com.traceability.contracts;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Immutable DTO containing deterministic aggregated audit facts for a
 * campaign/convocatoria. Distinct from {@link AuditFactsDTO}, which covers
 * a single donation.
 *
 * Ref: ADR-040 (ConvocatoriaAuditFacts), resolution of C1.
 *
 * Plan B5 (DD-36, Q-DIA-4): {@code currency} and one read instant per source ({@code fundingReadAt} for the
 * campaign and its ledger, {@code deliveriesReadAt} for the asset streams). The sources are read independently
 * (DD-35). {@code currency}, {@code targetAmount}, {@code targetPolicy} and {@code clearedAmount} are null for a
 * campaign that does not accept {@code MONETARY}.
 */
public record CampaignAuditFactsDTO(
    String campaignRef,
    String status,
    String organizationRef,
    BigDecimal targetAmount,
    String targetPolicy,
    BigDecimal clearedAmount,
    BigDecimal unitsDelivered,
    long distinctRecipients,
    String currency,
    Instant fundingReadAt,
    Instant deliveriesReadAt,
    Instant generatedAt
) {
    public CampaignAuditFactsDTO {
        if (campaignRef == null || campaignRef.isBlank()) {
            throw new IllegalArgumentException("campaignRef cannot be null or blank");
        }
        if (organizationRef == null || organizationRef.isBlank()) {
            throw new IllegalArgumentException("organizationRef cannot be null or blank");
        }
        if (distinctRecipients < 0) {
            throw new IllegalArgumentException("distinctRecipients cannot be negative");
        }
    }
}