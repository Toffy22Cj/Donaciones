package com.traceability.app.web.donation;

/** Webhook simulado sin firma válida → 401 con cuerpo fijo; no se procesa nada. */
public class InvalidWebhookSignatureException extends RuntimeException {
    public InvalidWebhookSignatureException() {
        super("invalid webhook signature");
    }
}
