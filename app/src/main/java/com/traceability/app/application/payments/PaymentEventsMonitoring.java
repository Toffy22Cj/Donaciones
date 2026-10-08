package com.traceability.app.application.payments;

import com.traceability.convocatoria.application.service.GatewayPaymentService;
import org.springframework.jmx.export.annotation.ManagedAttribute;
import org.springframework.jmx.export.annotation.ManagedResource;
import org.springframework.stereotype.Component;

/**
 * Contador de confirmaciones de pago no aceptables por JMX (Enmienda 3 de ADR-037, D4; E3-Q2): dinero recibido para
 * una intención {@code FAILED} o {@code EXPIRED_UNKNOWN}. Queda pendiente de P1.
 */
@Component
@ManagedResource(objectName = "com.traceability.app.application.payments:type=PaymentEventsMonitoring",
        description = "Unacceptable payment events pending resolution (ADR-037 amendment 3, D4; P1)")
public class PaymentEventsMonitoring {

    private final GatewayPaymentService gatewayPayments;

    public PaymentEventsMonitoring(GatewayPaymentService gatewayPayments) {
        this.gatewayPayments = gatewayPayments;
    }

    @ManagedAttribute(description = "Unacceptable payment events recorded in unacceptable_payment_events")
    public long getUnacceptablePaymentEvents() {
        return gatewayPayments.unacceptablePaymentEventsRecorded();
    }

    @ManagedAttribute(description = "Unacceptable payment events received since this instance started")
    public long getUnacceptablePaymentEventsSinceStart() {
        return gatewayPayments.unacceptablePaymentEventsSinceStart();
    }
}
