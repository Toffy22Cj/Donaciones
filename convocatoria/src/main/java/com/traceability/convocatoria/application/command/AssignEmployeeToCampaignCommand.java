package com.traceability.convocatoria.application.command;

/**
 * {@code AssignEmployeeToCampaign} (Enmienda §4.1). Firma provisional, reportada en implementation_plan.md §16.
 */
public record AssignEmployeeToCampaignCommand(String commandId, String actorAccountId, String campaignRef,
                                              String employeeRef) {
}
