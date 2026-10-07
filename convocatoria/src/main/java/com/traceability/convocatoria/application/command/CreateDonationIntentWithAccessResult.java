package com.traceability.convocatoria.application.command;

/**
 * Resultado de CV-11 con la credencial de consulta y la sesión de pago (Enmienda 3 de ADR-037, D3 y D6).
 *
 * @param statusToken        el token en claro, <b>solo</b> en la llamada que creó la intención (D6: "se entrega una sola
 *                           vez"); un duplicado lo recibe {@code null}
 * @param paymentRedirectUrl solo para {@code GATEWAY}
 */
public record CreateDonationIntentWithAccessResult(String intentId, String fundId, String statusToken,
                                                   String paymentRedirectUrl) {
}
