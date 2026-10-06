package com.traceability.convocatoria.application.command;

/**
 * Resultado de aplicar los fondos de una {@code DonationIntent} {@code CONFIRMED} (ADR-037 Enmienda 2 §3.3).
 * {@code appliedNow} indica si esta llamada produjo el efecto; ante un duplicado vale {@code false} y el resto de
 * campos es el resultado original guardado en el reclamo {@code APPLY_FUNDS} (N12).
 */
public record ApplyFundsResult(String intentId, String campaignRef, long amount, boolean appliedNow) {
}
