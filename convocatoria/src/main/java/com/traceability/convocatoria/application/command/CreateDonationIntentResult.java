package com.traceability.convocatoria.application.command;

/**
 * Resultado original de crear la intención: {@code intentId} y {@code fundId} (ID; implementation_plan.md §7.3).
 */
public record CreateDonationIntentResult(String intentId, String fundId) {
}
