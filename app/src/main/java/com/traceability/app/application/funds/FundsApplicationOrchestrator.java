package com.traceability.app.application.funds;

import com.mongodb.MongoException;
import com.traceability.convocatoria.application.command.ApplyFundsResult;
import com.traceability.convocatoria.application.idempotency.CommandClaimCollisionException;
import com.traceability.convocatoria.application.service.CampaignFundingLedgerService;
import com.traceability.convocatoria.application.service.ConvocatoriaTransactionRetryHelper;
import com.traceability.convocatoria.application.service.FundsApplicationRecoveryService;
import com.traceability.convocatoria.domain.exception.CampaignFundingLimitExceededException;
import com.traceability.convocatoria.domain.exception.DonationIntentNotConfirmedException;
import com.traceability.convocatoria.domain.exception.DonationIntentNotFoundException;
import com.traceability.convocatoria.domain.model.DonationIntent;
import com.traceability.core.application.command.FundCommandService;
import com.traceability.core.application.exception.ConcurrencyConflictException;
import com.traceability.core.domain.event.SystemActor;
import com.traceability.core.domain.fund.OrganizationRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Orquestador de la aplicación de fondos de una {@code DonationIntent} {@code CONFIRMED} (ADR-045; ADR-037 §2.3,
 * Enmienda 2 §3.2). Tx 2 en una sola transacción MongoDB: reclamo {@code APPLY_FUNDS} + ledger + {@code fundsAppliedAt}
 * ({@code convocatoria}) + génesis del {@code Fund} sin reintento interno (T1, {@code core}). Sin outbox (D-P8,
 * opción A). El reintento acotado envuelve la transacción completa (E1 §6), con la política de {@code convocatoria}
 * (error transitorio de MongoDB o colisión del reclamo).
 * <p>
 * Un intento por llamada; el resultado se clasifica con la taxonomía de ADR-045 §2.3 y, si falla, se registra en una
 * escritura propia después del rollback. La seguridad con varias instancias la da la barrera (§2.7): no hay bloqueos
 * propios. Lo usan el disparo inmediato tras confirmar y el scheduler de respaldo; también lo usará el webhook (P3).
 */
@Service
public class FundsApplicationOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(FundsApplicationOrchestrator.class);

    /** Actor de sistema de la génesis (Enmienda 2 §3.2): la autorización humana del movimiento es la de la confirmación. */
    static final String SYSTEM_ACTOR = "funds-application-orchestrator";
    /** Prefijo del {@code commandId} de la génesis: determinista por intención, así que una repetición es un no-op. */
    static final String GENESIS_COMMAND_PREFIX = "APPLY_FUNDS:";

    private final ConvocatoriaTransactionRetryHelper transactions;
    private final CampaignFundingLedgerService ledger;
    private final FundsApplicationRecoveryService recovery;
    private final FundCommandService funds;
    private final FundsApplicationProperties properties;
    private final Clock clock;

    public FundsApplicationOrchestrator(ConvocatoriaTransactionRetryHelper transactions,
                                        CampaignFundingLedgerService ledger,
                                        FundsApplicationRecoveryService recovery,
                                        FundCommandService funds,
                                        FundsApplicationProperties properties,
                                        ObjectProvider<Clock> clock) {
        this.transactions = transactions;
        this.ledger = ledger;
        this.recovery = recovery;
        this.funds = funds;
        this.properties = properties;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    /** Un intento de aplicación de la intención; nunca lanza por un fallo clasificado (ADR-045 §2.3). */
    public FundsApplicationOutcome apply(String intentId) {
        try {
            return transactions.executeWithRetry(() -> applyInTransaction(intentId));
        } catch (DonationIntentNotConfirmedException e) {
            return FundsApplicationOutcome.NOT_CONFIRMED;
        } catch (CampaignFundingLimitExceededException e) {
            return rejectOrRetry(intentId, e);
        } catch (RuntimeException e) {
            return isRetryable(e) ? recordRetryable(intentId, e) : quarantine(intentId, e);
        }
    }

    private FundsApplicationOutcome applyInTransaction(String intentId) {
        DonationIntent intent = recovery.findIntent(intentId).orElseThrow(
                () -> new DonationIntentNotFoundException("DonationIntent " + intentId + " not found"));
        ApplyFundsResult result = ledger.applyFundsForIntent(intentId);
        if (!result.appliedNow()) {
            return FundsApplicationOutcome.ALREADY_APPLIED;
        }
        funds.clearFundsGenesisWithinTransaction(GENESIS_COMMAND_PREFIX + intentId, intent.getFundId(),
                new OrganizationRef(intent.getOrganizationRef()), intent.getCampaignRef(), intent.getDonorRef(),
                intent.getCurrency(), intent.getAmount(), intentId, new SystemActor(SYSTEM_ACTOR));
        return FundsApplicationOutcome.APPLIED;
    }

    private FundsApplicationOutcome rejectOrRetry(String intentId, CampaignFundingLimitExceededException cause) {
        try {
            if (ledger.rejectFundingIfPermanentlyUnfundable(intentId)) {
                return FundsApplicationOutcome.FUNDING_REJECTED;
            }
            // La operación comprueba la permanencia por sí misma; si no la confirma, es reintentable (Enmienda 2 §4).
            return recordRetryable(intentId, cause);
        } catch (RuntimeException e) {
            return quarantine(intentId, e);
        }
    }

    private FundsApplicationOutcome recordRetryable(String intentId, RuntimeException cause) {
        log.warn("Retryable funds application failure for intent {}: {}", intentId, cause.getClass().getSimpleName());
        Optional<DonationIntent.ApplicationTracking> tracking =
                recovery.recordApplicationFailure(intentId, cause.getClass().getSimpleName(), false);
        if (tracking.isEmpty()) {
            return noLongerPending(intentId);
        }
        DonationIntent.ApplicationTracking t = tracking.get();
        Instant now = clock.instant();
        boolean exhausted = t.attempts() >= properties.maxAttempts()
                || (t.firstAttemptAt() != null
                    && Duration.between(t.firstAttemptAt(), now).compareTo(properties.retryWindow()) > 0);
        if (exhausted) {
            recovery.quarantineApplication(intentId);
            log.error("Funds application for intent {} quarantined after {} attempts since {}", intentId,
                    t.attempts(), t.firstAttemptAt());
            return FundsApplicationOutcome.QUARANTINED;
        }
        return FundsApplicationOutcome.RETRYABLE_FAILURE;
    }

    private FundsApplicationOutcome quarantine(String intentId, RuntimeException cause) {
        log.error("Funds application anomaly for intent {}; quarantined", intentId, cause);
        Optional<DonationIntent.ApplicationTracking> tracking =
                recovery.recordApplicationFailure(intentId, cause.getClass().getSimpleName(), true);
        return tracking.isEmpty() ? noLongerPending(intentId) : FundsApplicationOutcome.QUARANTINED;
    }

    /** La intención dejó de estar {@code CONFIRMED} sin aplicar entre el intento y su registro. */
    private FundsApplicationOutcome noLongerPending(String intentId) {
        return recovery.findIntent(intentId)
                .filter(i -> i.getApplicationTracking().fundsAppliedAt() != null)
                .map(i -> FundsApplicationOutcome.ALREADY_APPLIED)
                .orElse(FundsApplicationOutcome.NOT_CONFIRMED);
    }

    /**
     * Reintentable (ADR-045 §2.3): error transitorio de MongoDB, colisión del reclamo de {@code convocatoria} o
     * conflicto de concurrencia de la génesis, ya agotado el reintento acotado de la transacción.
     */
    static boolean isRetryable(Throwable ex) {
        for (Throwable current = ex; current != null; current = current.getCause()) {
            if (current instanceof CommandClaimCollisionException || current instanceof ConcurrencyConflictException) {
                return true;
            }
            if (current instanceof MongoException mongo
                    && mongo.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL)) {
                return true;
            }
        }
        return false;
    }
}
