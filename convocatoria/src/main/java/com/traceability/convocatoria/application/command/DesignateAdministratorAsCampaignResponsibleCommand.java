package com.traceability.convocatoria.application.command;

/**
 * {@code DesignateAdministratorAsCampaignResponsible} (Enmienda §4.1). Firma provisional, reportada en
 * implementation_plan.md §16.
 */
public record DesignateAdministratorAsCampaignResponsibleCommand(String commandId, String actorAccountId,
                                                                 String campaignRef, String administratorRef) {
}
