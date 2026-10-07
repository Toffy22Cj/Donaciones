package com.traceability.convocatoria.application.port.out;

/**
 * Sesión de pago en el proveedor (Enmienda 3 de ADR-037, D3). En la demo la implementa el proveedor simulado de
 * {@code app}, que solo existe con {@code traceability.demo.simulated-payments=true}. No hay implementación real.
 */
public interface PaymentProviderPort {

    PaymentSession createSession(String intentId, long amount, String currency);

    record PaymentSession(String paymentProvider, String paymentSessionId, String redirectUrl) {}
}
