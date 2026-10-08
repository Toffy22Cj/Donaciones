package com.traceability.app.application.funds;

import com.traceability.convocatoria.application.command.ConfirmDonationIntentCommand;
import com.traceability.convocatoria.application.service.DonationIntentService;
import org.springframework.stereotype.Service;

/**
 * Confirmación manual con disparo inmediato de la aplicación (ADR-045 §2.1): <b>Tx 1</b> confirma en
 * {@code convocatoria} (commit propio) y, solo si esta llamada confirmó, <b>Tx 2</b> aplica los fondos. El resultado
 * de la confirmación no depende de la aplicación: si la Tx 2 falla o el proceso cae entre ambas, la intención queda
 * {@code CONFIRMED} y el scheduler de respaldo la recupera. La autorización (solo {@code ADMINISTRATOR}, nunca
 * intenciones de pasarela) es la de {@code DonationIntentService.confirmDonationIntent} (Enmienda 2 §3.1).
 */
@Service
public class ConfirmAndApplyDonationIntentUseCase {

    private final DonationIntentService donationIntents;
    private final FundsApplicationOrchestrator orchestrator;

    public ConfirmAndApplyDonationIntentUseCase(DonationIntentService donationIntents,
                                                FundsApplicationOrchestrator orchestrator) {
        this.donationIntents = donationIntents;
        this.orchestrator = orchestrator;
    }

    public Result execute(ConfirmDonationIntentCommand command) {
        boolean confirmed = donationIntents.confirmDonationIntent(command);
        if (!confirmed) {
            return new Result(false, null);
        }
        return new Result(true, orchestrator.apply(command.intentId()));
    }

    /**
     * @param confirmed   si esta llamada confirmó la intención
     * @param application resultado del disparo inmediato, o {@code null} si no se confirmó en esta llamada
     */
    public record Result(boolean confirmed, FundsApplicationOutcome application) {
    }
}
