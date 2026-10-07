package com.traceability.app.application.payments;

import com.traceability.convocatoria.application.port.out.PaymentProviderPort;
import com.traceability.convocatoria.domain.model.PaymentProviders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Proveedor de pago de la demo (Enmienda 3 de ADR-037, D3). <b>Solo existe con
 * {@code traceability.demo.simulated-payments=true}</b> (primera barrera de E3-Q1); sin la propiedad, ninguna
 * intención {@code GATEWAY} puede crearse. No mueve dinero: devuelve una sesión aleatoria y una URL de checkout
 * simulado, que la web de la demo usa para disparar el webhook simulado.
 */
@Component
@ConditionalOnProperty(name = "traceability.demo.simulated-payments", havingValue = "true")
public class SimulatedPaymentProvider implements PaymentProviderPort {

    @Override
    public PaymentSession createSession(String intentId, long amount, String currency) {
        String sessionId = "sim_" + UUID.randomUUID();
        return new PaymentSession(PaymentProviders.SIMULATED, sessionId, "/demo/checkout/" + sessionId);
    }
}
