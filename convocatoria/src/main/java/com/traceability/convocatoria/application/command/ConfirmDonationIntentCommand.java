package com.traceability.convocatoria.application.command;

/**
 * Confirmación manual {@code PENDING → CONFIRMED} (Enmienda §5.2–§5.3; N8): {@code actorAccountId} es la cuenta del
 * actor, que debe ser {@code ADMINISTRATOR} de la organización de la intención (ADR-037 Enmienda 2 §3.1); el
 * momento lo fija el caso de uso y el medio es el de la intención. No aplica fondos (F-2). Firma provisional,
 * reportada en implementation_plan.md §16.
 */
public record ConfirmDonationIntentCommand(String intentId, String actorAccountId, String reference) {
}
