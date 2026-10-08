package com.traceability.app.application.anchoring;

import com.traceability.core.application.port.out.UnanchoredEventRepositoryPort;
import com.traceability.crypto.application.port.out.BatchReleaseAuditPort;
import com.traceability.crypto.application.port.out.BatchReleaseAuditPort.BatchRelease;
import com.traceability.crypto.application.port.out.MerkleBatchRepositoryPort;
import com.traceability.crypto.domain.MerkleBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;

/**
 * Salida manual de un batch {@code COLLECTING_FAILED} (Enmienda 1 de ADR-039 §2.3, aprobada por Carlos el 2026-10-08),
 * nunca automática; la expone {@link CollectingFailedBatchAdminOperations} por JMX, como {@code resolveStuckBatch}.
 * <ul>
 *   <li>{@code RETRY}: {@code COLLECTING_FAILED → COLLECTING} con {@code recoveryAttempts = 0}.</li>
 *   <li>{@code RELEASE}: {@code COLLECTING_FAILED → RELEASED} y, en la misma transacción, {@code merkleBatchId = null}
 *   solo en los eventos de ese batch dentro de su cobertura, más el registro de auditoría (batch, cobertura, eventos,
 *   operador y motivo). El batch conserva su cobertura como registro.</li>
 * </ul>
 * Las dos solo actúan sobre un batch en {@code COLLECTING_FAILED} (transición condicional en la base); si no,
 * {@link IllegalStateException} y nada cambia.
 */
@Service
public class CollectingFailedBatchService {

    private static final Logger log = LoggerFactory.getLogger(CollectingFailedBatchService.class);

    private final MerkleBatchRepositoryPort batches;
    private final UnanchoredEventRepositoryPort events;
    private final BatchReleaseAuditPort audit;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public CollectingFailedBatchService(MerkleBatchRepositoryPort batches, UnanchoredEventRepositoryPort events,
                                        BatchReleaseAuditPort audit, TransactionTemplate transactions,
                                        ObjectProvider<Clock> clock) {
        this.batches = batches;
        this.events = events;
        this.audit = audit;
        this.transactions = transactions;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    public void retry(String batchId) {
        if (!batches.retryCollectingFailed(batchId)) {
            throw new IllegalStateException("Batch " + batchId + " is not COLLECTING_FAILED");
        }
        log.warn("Batch {} RETRY: COLLECTING_FAILED -> COLLECTING (recoveryAttempts = 0)", batchId);
    }

    public void release(String batchId, String operator, String reason) {
        if (operator == null || operator.isBlank() || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("RELEASE needs an operator and a reason for the audit");
        }
        MerkleBatch batch = batches.findByBatchId(batchId)
                .orElseThrow(() -> new IllegalStateException("Batch " + batchId + " not found"));
        List<String> released = transactions.execute(status -> {
            if (!batches.markReleased(batchId)) {
                throw new IllegalStateException("Batch " + batchId + " is not COLLECTING_FAILED");
            }
            List<String> ids = events.releaseClaim(batchId, batch.coverage());
            audit.record(new BatchRelease(batchId, batch.coverage(), ids, operator, reason, clock.instant()));
            return ids;
        });
        log.warn("Batch {} RELEASE: COLLECTING_FAILED -> RELEASED; {} event(s) claimable again", batchId,
                released == null ? 0 : released.size());
    }
}
