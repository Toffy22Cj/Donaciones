package com.traceability.convocatoria.application.command;

import com.traceability.convocatoria.domain.model.PaymentMethod;

/**
 * Crear {@code DonationIntent} (ADR-037 §2.6; Enmienda §5.1; ID, implementation_plan.md §7.3, §9.1): pública u
 * opcional JWT, sin política de roles. {@code commandId} obligatorio; su transporte HTTP es de ADR-041 §7-A.4.
 * Firma provisional, reportada en §16.
 */
public record CreateDonationIntentCommand(String commandId, String publicCode, String donorRef, long amount,
                                          String currency, PaymentMethod paymentMethod) {
}
