package com.traceability.app.application.funds;

import com.traceability.convocatoria.application.service.CampaignFundingLedgerService;
import com.traceability.convocatoria.application.service.FundsApplicationRecoveryService;
import com.traceability.convocatoria.domain.model.DonationIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Scheduler de respaldo de la aplicación de fondos (ADR-045 §2.2): recupera intenciones {@code CONFIRMED} sin
 * aplicar con la consulta de {@code convocatoria}, que ya ordena por equidad y excluye cuarentena y P9 (§2.4). Como
 * mucho un intento por intención y ejecución, cada uno en su propia Tx 2. Mismo patrón que
 * {@code BlockchainAnchorProducer}.
 * <p>
 * Solo existe si {@code convocatoria.funds-application.recovery.enabled=true}; está deshabilitado por defecto, también
 * en producción, hasta que existan P1, T1 y P8 (Enmienda 2 §4: no se habilita el dinero real sin P1).
 */
@Component
@ConditionalOnProperty(prefix = "convocatoria.funds-application.recovery", name = "enabled", havingValue = "true")
public class FundsApplicationRecoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(FundsApplicationRecoveryScheduler.class);

    private final CampaignFundingLedgerService ledger;
    private final FundsApplicationRecoveryService recovery;
    private final FundsApplicationOrchestrator orchestrator;
    private final FundsApplicationProperties properties;

    public FundsApplicationRecoveryScheduler(CampaignFundingLedgerService ledger,
                                             FundsApplicationRecoveryService recovery,
                                             FundsApplicationOrchestrator orchestrator,
                                             FundsApplicationProperties properties) {
        this.ledger = ledger;
        this.recovery = recovery;
        this.orchestrator = orchestrator;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${convocatoria.funds-application.recovery.fixed-delay:PT1M}",
            initialDelayString = "${convocatoria.funds-application.recovery.initial-delay:PT1M}")
    public void scheduledRun() {
        runOnce();
    }

    /** Una ejecución: un intento por intención recuperable del lote y las métricas de ADR-045 §2.6. */
    public RunSummary runOnce() {
        String runId = UUID.randomUUID().toString();
        MDC.put("runId", runId);
        try {
            Map<FundsApplicationOutcome, Integer> outcomes = new EnumMap<>(FundsApplicationOutcome.class);
            List<DonationIntent> batch = ledger.findConfirmedPendingApplication(properties.batchSize());
            for (DonationIntent intent : batch) {
                MDC.put("correlationId", runId + ":" + intent.getIntentId());
                long start = System.nanoTime();
                FundsApplicationOutcome outcome = orchestrator.apply(intent.getIntentId());
                outcomes.merge(outcome, 1, Integer::sum);
                log.info("Funds application attempt intentId={} campaignRef={} fundId={} outcome={} durationMs={}",
                        intent.getIntentId(), intent.getCampaignRef(), intent.getFundId(), outcome,
                        (System.nanoTime() - start) / 1_000_000);
                MDC.remove("correlationId");
            }
            FundsApplicationRecoveryService.RecoveryMetrics metrics = recovery.metrics();
            log.info("Funds application recovery run {}: batch={} outcomes={} excludedByP9={} quarantined={} oldestPendingConfirmedAt={}",
                    runId, batch.size(), outcomes, metrics.excludedByCloseOnTargetClose(), metrics.quarantined(),
                    metrics.oldestPendingConfirmedAt());
            if (metrics.excludedByCloseOnTargetClose() > 0 || metrics.quarantined() > 0) {
                log.warn("Funds application needs attention: {} intent(s) excluded by P9 (CLOSE_ON_TARGET + CLOSE, R4 "
                        + "pending) and {} in quarantine", metrics.excludedByCloseOnTargetClose(), metrics.quarantined());
            }
            return new RunSummary(runId, batch.size(), Map.copyOf(outcomes), metrics);
        } finally {
            MDC.remove("correlationId");
            MDC.remove("runId");
        }
    }

    public record RunSummary(String runId, int batchSize, Map<FundsApplicationOutcome, Integer> outcomes,
                             FundsApplicationRecoveryService.RecoveryMetrics metrics) {

        public int count(FundsApplicationOutcome outcome) {
            return outcomes.getOrDefault(outcome, 0);
        }
    }
}
