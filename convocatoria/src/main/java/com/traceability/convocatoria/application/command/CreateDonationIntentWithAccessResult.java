package com.traceability.convocatoria.application.command;

/**
 * Resultado de CV-11 con la credencial de consulta y la sesión de pago (Enmienda 3 de ADR-037, D3 y D6).
 *
 * @param statusToken        el token en claro; un reenvío recibe uno nuevo y el anterior deja de valer (DD-18 sustituida
 *                           por Carlos); {@code null} solo para una intención sin credencial (creada antes de la Enmienda 3)
 * @param paymentRedirectUrl solo para {@code GATEWAY}
 */
public record CreateDonationIntentWithAccessResult(String intentId, String fundId, String statusToken,
                                                   String paymentRedirectUrl) {
}
