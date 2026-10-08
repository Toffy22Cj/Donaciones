package com.traceability.convocatoria.application.port.out;

import java.time.Instant;

/**
 * Registro persistente de dinero recibido para una intención no pendiente (Enmienda 3 de ADR-037, D4; precisión de
 * Carlos a Q3). Atado a P1 como vía de resolución pendiente: hasta que P1 exista, se ve y se cuenta, no se resuelve.
 */
public interface UnacceptablePaymentEventPort {

    /** Idempotente por {@code (paymentProvider, providerEventId)}: un reenvío del mismo evento no se cuenta dos veces. */
    void record(UnacceptablePaymentEvent event);

    long count();

    record UnacceptablePaymentEvent(String paymentProvider, String providerEventId, String intentId, long amount,
                                    String currency, String reason, Instant receivedAt) {}
}
