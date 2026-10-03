package com.traceability.convocatoria.domain.exception;

/**
 * Una `DonationIntent` de pasarela (`GATEWAY`, `PAYMENT_PROVIDER`) no se confirma por el camino manual: la
 * confirmación humana nunca sustituye al webhook (Enmienda §5.2, N8; ADR-037 Enmienda 2 §3.1).
 */
public class GatewayIntentManualConfirmationNotAllowedException extends ConvocatoriaDomainException {

    public GatewayIntentManualConfirmationNotAllowedException(String message) {
        super(message);
    }
}
