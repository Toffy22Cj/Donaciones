package com.traceability.app.web.donation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.api.web.InvalidRequestFieldException;
import com.traceability.app.application.payments.ConfirmGatewayPaymentUseCase;
import com.traceability.app.application.payments.SimulatedWebhookSignature;
import com.traceability.convocatoria.domain.model.PaymentProviders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Webhook del proveedor simulado (Enmienda 3 de ADR-037, D1, D2 y D4; requisitos del plan de cierre, B6):
 * <ol>
 *   <li>llama al <b>mismo</b> caso de uso que usará el webhook real ({@link ConfirmGatewayPaymentUseCase}: Tx 1 → Tx 2);</li>
 *   <li>solo existe con {@code traceability.demo.simulated-payments=true}: en cualquier otro caso la ruta no existe (404);</li>
 *   <li>la intención queda con {@code paymentProvider = SIMULATED}, distinta de cualquier proveedor real.</li>
 * </ol>
 * Responde 200 sin cuerpo a todo evento procesado, incluidos los duplicados y los no aceptables: nunca el
 * {@code trackingCode} (la respuesta va al proveedor, no al donante).
 */
@ConditionalOnProperty(name = "traceability.demo.simulated-payments", havingValue = "true")
public class SimulatedPaymentWebhookController {

    public static final String SIGNATURE_HEADER = "X-Simulated-Signature";

    private final ConfirmGatewayPaymentUseCase payments;
    private final SimulatedWebhookSignature signature;
    private final ObjectMapper json = new ObjectMapper();

    public SimulatedPaymentWebhookController(ConfirmGatewayPaymentUseCase payments, SimulatedWebhookSignature signature) {
        this.payments = payments;
        this.signature = signature;
    }

    @PostMapping("/api/v1/webhooks/payments")
    public ResponseEntity<Void> receive(@RequestHeader(name = SIGNATURE_HEADER, required = false) String sig,
                                        @RequestBody(required = false) String rawBody) {
        if (!signature.verify(rawBody, sig)) {
            throw new InvalidWebhookSignatureException();
        }
        JsonNode event;
        try {
            event = json.readTree(rawBody);
        } catch (Exception e) {
            throw new InvalidRequestFieldException("body");
        }
        String type = text(event, "type");
        String sessionId = text(event, "paymentSessionId");
        String eventId = text(event, "providerEventId");
        switch (type) {
            case "payment.confirmed" -> payments.confirm(PaymentProviders.SIMULATED, sessionId, eventId,
                    amount(event), text(event, "currency"));
            case "payment.failed" -> payments.fail(PaymentProviders.SIMULATED, sessionId, eventId);
            default -> throw new InvalidRequestFieldException("type");
        }
        return ResponseEntity.ok().build();
    }

    private static String text(JsonNode event, String field) {
        JsonNode node = event.get(field);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw new InvalidRequestFieldException(field);
        }
        return node.asText();
    }

    private static long amount(JsonNode event) {
        String value = text(event, "amount");
        if (!value.matches("^[0-9]{1,18}$")) {
            throw new InvalidRequestFieldException("amount");
        }
        return Long.parseLong(value);
    }
}
