package com.traceability.convocatoria.application.command;

/**
 * Resolución interna {@code publicCode → campaignRef + organizationRef} (ADR-037 §2.6; implementation_plan.md §1.2, §5).
 */
public record CampaignReference(String campaignRef, String organizationRef) {
}
