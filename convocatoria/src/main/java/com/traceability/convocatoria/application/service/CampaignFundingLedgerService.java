package com.traceability.convocatoria.application.service;

import com.traceability.convocatoria.application.command.ApplyFundsResult;
import com.traceability.convocatoria.application.idempotency.CommandType;
import com.traceability.convocatoria.application.idempotency.ProcessedCommand;
import com.traceability.convocatoria.application.port.out.CampaignFundingLedgerRepositoryPort;
import com.traceability.convocatoria.application.port.out.DonationIntentRepositoryPort;
import com.traceability.convocatoria.application.port.out.ProcessedCommandPort;
import com.traceability.convocatoria.domain.exception.CampaignFundingLimitExceededException;
import com.traceability.convocatoria.domain.exception.CampaignNotFoundException;
import com.traceability.convocatoria.domain.exception.CloseOnTargetCloseNotSupportedException;
import com.traceability.convocatoria.domain.exception.DonationIntentNotConfirmedException;
import com.traceability.convocatoria.domain.exception.DonationIntentNotFoundException;
import com.traceability.convocatoria.domain.exception.InvalidFundingAmountException;
import com.traceability.convocatoria.domain.model.CampaignFundingLedger;
import com.traceability.convocatoria.domain.model.DonationIntent;
import com.traceability.convocatoria.domain.model.DonationIntentStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Aplicación de fondos de una {@code DonationIntent} ya {@code CONFIRMED} al {@code CampaignFundingLedger}, por
 * política (ADR-037 §2.2; Enmienda §3.1, §3.3; implementation_plan.md §8):
 * <ul>
 *   <li>{@code FLEXIBLE} y {@code CLOSE_ON_TARGET + ACCEPT_EXCESS}: {@code updateOne({campaignRef}, {$inc})};</li>
 *   <li>{@code STRICT} y {@code CLOSE_ON_TARGET + REJECT_EXCESS}: {@code updateOne({campaignRef, clearedAmount ≤
 *       targetAmount − amount}, {$inc})}, rechazo completo de la donación (sin aceptación parcial);</li>
 *   <li>{@code CLOSE_ON_TARGET + CLOSE}: no soportado en este corte (R4).</li>
 * </ul>
 * Confirmar y aplicar son actos separados (F-1, F-2): esta operación no confirma; consume una intención ya confirmada.
 * La barrera de idempotencia es el registro de comandos procesados del módulo, con el comando de sistema
 * {@link CommandType#APPLY_FUNDS} y {@code commandId = intentId} (ADR-037 Enmienda 2 §3.3). La composición
 * con {@code Fund} y el outbox pertenece al orquestador de {@code app} (ADR-037 §2.3; Enmienda 2 §3.2), que todavía no
 * existe; la recuperación automática la regula ADR-043.
 */
@Service
public class CampaignFundingLedgerService {

    private final CampaignFundingLedgerRepositoryPort ledgers;
    private final DonationIntentRepositoryPort donationIntents;
    private final ProcessedCommandPort processedCommands;
    private final ConvocatoriaTransactionRetryHelper transactionRetryHelper;

    public CampaignFundingLedgerService(CampaignFundingLedgerRepositoryPort ledgers,
                                        DonationIntentRepositoryPort donationIntents,
                                        ProcessedCommandPort processedCommands,
                                        ConvocatoriaTransactionRetryHelper transactionRetryHelper) {
        this.ledgers = ledgers;
        this.donationIntents = donationIntents;
        this.processedCommands = processedCommands;
        this.transactionRetryHelper = transactionRetryHelper;
    }

    /**
     * Aplica al ledger el importe de una intención {@code CONFIRMED}, como mucho una vez por intención. En la misma
     * transacción: comprobar que la intención está {@code CONFIRMED} (si no, {@link DonationIntentNotConfirmedException})
     * → reclamar la barrera {@code (APPLY_FUNDS, intentId)} → incrementar el ledger por política → guardar el
     * resultado original en el reclamo. La barrera se reclama antes del incremento, así que una repetición (secuencial,
     * concurrente o tras un reinicio) encuentra el reclamo y no incrementa. Devuelve el resultado: el nuevo, con
     * {@code appliedNow = true}, o, ante un duplicado, el original guardado, con {@code appliedNow = false} y sin
     * modificarlo (N12).
     * <p>
     * Si el ledger rechaza la aplicación o falla cualquier otro paso, la excepción se propaga sin traducir, la
     * transacción se revierte con el reclamo y la intención sigue {@code CONFIRMED} (DH-3, DH-4). El rechazo permanente
     * no se marca aquí: lo marca {@link #rejectFundingIfPermanentlyUnfundable(String)} en otra transacción. Sin
     * transacción activa abre la suya con el reintento acotado del módulo ante {@code TransientTransactionError}.
     * Dentro de una transacción existente (el futuro orquestador de {@code app}) se une a ella sin reintento interno
     * (Enmienda §6; implementation_plan.md §8, T1) y la deja marcada para rollback ante cualquier excepción.
     */
    @Transactional(propagation = Propagation.SUPPORTS)
    public ApplyFundsResult applyFundsForIntent(String intentId) {
        Supplier<ApplyFundsResult> operation = () -> {
            DonationIntent intent = loadIntent(intentId);
            if (intent.getStatus() != DonationIntentStatus.CONFIRMED) {
                throw new DonationIntentNotConfirmedException("DonationIntent " + intentId + " is "
                        + intent.getStatus() + ", not CONFIRMED");
            }
            Optional<ProcessedCommand> previous = processedCommands.claimSystemCommand(CommandType.APPLY_FUNDS, intentId);
            if (previous.isPresent()) {
                Map<String, String> original = previous.get().result();
                return new ApplyFundsResult(original.get("intentId"), original.get("campaignRef"),
                        Long.parseLong(original.get("amount")), false);
            }
            increment(intent.getCampaignRef(), intent.getAmount());
            processedCommands.saveSystemCommandResult(CommandType.APPLY_FUNDS, intentId, Map.of(
                    "intentId", intentId,
                    "campaignRef", intent.getCampaignRef(),
                    "amount", String.valueOf(intent.getAmount())));
            return new ApplyFundsResult(intentId, intent.getCampaignRef(), intent.getAmount(), true);
        };
        return inTransaction(operation);
    }

    /**
     * Marca {@code CONFIRMED → FUNDING_REJECTED} solo si la aplicación de la intención está rechazada de forma
     * permanente en el alcance actual (ADR-037 Enmienda 2 §4): el ledger tiene condición de capacidad y la intención no
     * cabe ({@link CampaignFundingLimitExceededException}: la meta no se edita y el ledger no tiene operación de
     * liberación). {@code CLOSE_ON_TARGET + CLOSE} ({@link CloseOnTargetCloseNotSupportedException}) <b>no</b> es
     * rechazo permanente mientras R4 esté pendiente: la intención sigue {@code CONFIRMED}. La operación comprueba ella
     * misma la condición en lugar de fiarse del llamador, así que un conflicto transitorio nunca termina en
     * {@code FUNDING_REJECTED}. No hace nada si la intención no está {@code CONFIRMED} o ya está aplicada. Las
     * anomalías de invariante ({@link CampaignNotFoundException}) se propagan. Escritura condicional sobre
     * {@code status = CONFIRMED}; devuelve si se marcó en esta llamada.
     */
    @Transactional(propagation = Propagation.SUPPORTS)
    public boolean rejectFundingIfPermanentlyUnfundable(String intentId) {
        Supplier<Boolean> operation = () -> {
            DonationIntent intent = loadIntent(intentId);
            if (intent.getStatus() != DonationIntentStatus.CONFIRMED
                    || processedCommands.findSystemCommand(CommandType.APPLY_FUNDS, intentId).isPresent()) {
                return false;
            }
            CampaignFundingLedger ledger = ledgers.findByCampaignRef(intent.getCampaignRef())
                    .orElseThrow(() -> new CampaignNotFoundException("No funding ledger for campaign "
                            + intent.getCampaignRef()));
            if (!isPermanentlyUnfundable(ledger, intent.getAmount())) {
                return false;
            }
            return donationIntents.markFundingRejectedIfConfirmed(intentId);
        };
        return inTransaction(operation);
    }

    /**
     * Intenciones {@code CONFIRMED} cuya aplicación no se ha completado (sin reclamo {@code APPLY_FUNDS}),
     * para que la recuperación automática las vuelva a procesar (ADR-037 Enmienda 2 §5; ADR-043). Excluye las aplicadas,
     * las {@code FUNDING_REJECTED}, las que no están confirmadas y, mientras R4 no exista, las de convocatorias
     * {@code CLOSE_ON_TARGET + CLOSE} (P9, opción a): no son aplicables ni rechazables, así que quedan fuera de la cola
     * automática; cuando se implemente R4 habrá que definir cómo vuelven a ser elegibles.
     */
    public List<DonationIntent> findConfirmedPendingApplication(int limit) {
        return donationIntents.findConfirmedPendingApplication(limit);
    }

    private static boolean isPermanentlyUnfundable(CampaignFundingLedger ledger, long amount) {
        boolean capacityLimited;
        try {
            capacityLimited = ledger.isCapacityLimited();
        } catch (CloseOnTargetCloseNotSupportedException e) {
            return false; // R4 pendiente: no es rechazo permanente (Enmienda 2 §4)
        }
        return capacityLimited && ledger.clearedAmount() > ledger.targetAmount() - amount;
    }

    private <T> T inTransaction(Supplier<T> operation) {
        return TransactionSynchronizationManager.isActualTransactionActive()
                ? operation.get()
                : transactionRetryHelper.executeWithRetry(operation);
    }

    private DonationIntent loadIntent(String intentId) {
        return donationIntents.findById(intentId).orElseThrow(
                () -> new DonationIntentNotFoundException("DonationIntent " + intentId + " not found"));
    }

    private void increment(String campaignRef, long amount) {
        if (amount <= 0) {
            throw new InvalidFundingAmountException("amount must be strictly positive");
        }
        CampaignFundingLedger ledger = ledgers.findByCampaignRef(campaignRef)
                .orElseThrow(() -> new CampaignNotFoundException("No funding ledger for campaign " + campaignRef));
        boolean applied = ledger.isCapacityLimited()
                ? ledgers.incrementWithinTarget(campaignRef, amount, ledger.targetAmount())
                : ledgers.incrementUnconditionally(campaignRef, amount);
        if (!applied) {
            if (ledgers.findByCampaignRef(campaignRef).isEmpty()) {
                throw new CampaignNotFoundException("No funding ledger for campaign " + campaignRef);
            }
            throw new CampaignFundingLimitExceededException("Applying " + amount + " would exceed target "
                    + ledger.targetAmount() + " of campaign " + campaignRef);
        }
    }
}
