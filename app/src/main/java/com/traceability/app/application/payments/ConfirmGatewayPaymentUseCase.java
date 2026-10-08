package com.traceability.app.application.payments;

import com.traceability.app.application.funds.FundsApplicationOrchestrator;
import com.traceability.app.application.funds.FundsApplicationOutcome;
import com.traceability.convocatoria.application.service.GatewayPaymentService;
import com.traceability.convocatoria.application.service.GatewayPaymentService.Outcome;
import com.traceability.convocatoria.domain.model.DonationIntent;
import org.springframework.stereotype.Service;

/**
 * Confirmación por pasarela (Enmienda 3 de ADR-037, D1): <b>Tx 1</b> confirma en {@code convocatoria} y, solo si esta
 * llamada confirmó, <b>Tx 2</b> aplica los fondos con el <b>mismo</b> {@link FundsApplicationOrchestrator} de ADR-045
 * que la confirmación manual. Lo usa el webhook simulado y lo usará el real: no hay atajo que aplique fondos.
 */
@Service
public class ConfirmGatewayPaymentUseCase {

    private final GatewayPaymentService gatewayPayments;
    private final FundsApplicationOrchestrator orchestrator;
    private final com.traceability.convocatoria.application.port.out.DonationIntentRepositoryPort intents;

    public ConfirmGatewayPaymentUseCase(GatewayPaymentService gatewayPayments, FundsApplicationOrchestrator orchestrator,
                                        com.traceability.convocatoria.application.port.out.DonationIntentRepositoryPort intents) {
        this.gatewayPayments = gatewayPayments;
        this.orchestrator = orchestrator;
        this.intents = intents;
    }

    public Result confirm(String paymentProvider, String paymentSessionId, String providerEventId, long amount,
                          String currency) {
        Outcome outcome = gatewayPayments.confirmGatewayPayment(paymentProvider, paymentSessionId, providerEventId,
                amount, currency);
        if (outcome != Outcome.CONFIRMED) {
            return new Result(outcome, null);
        }
        String intentId = intents.findByPaymentSessionId(paymentSessionId).map(DonationIntent::getIntentId).orElseThrow();
        return new Result(outcome, orchestrator.apply(intentId));
    }

    public GatewayPaymentService.FailureOutcome fail(String paymentProvider, String paymentSessionId,
                                                     String providerEventId) {
        return gatewayPayments.failGatewayPayment(paymentProvider, paymentSessionId, providerEventId);
    }

    /** @param application resultado de la Tx 2, o {@code null} si esta llamada no confirmó */
    public record Result(Outcome confirmation, FundsApplicationOutcome application) {}
}
