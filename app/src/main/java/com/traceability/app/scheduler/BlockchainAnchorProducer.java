package com.traceability.app.scheduler;

import com.traceability.contracts.SequenceRange;
import com.traceability.core.application.port.out.UnanchoredEventRepositoryPort;
import com.traceability.crypto.application.port.out.MerkleBatchRepositoryPort;
import com.traceability.crypto.domain.AnchorStatus;
import com.traceability.crypto.domain.MerkleBatch;
import com.traceability.crypto.domain.MerkleTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class BlockchainAnchorProducer {

    private static final Logger log = LoggerFactory.getLogger(BlockchainAnchorProducer.class);

    private final UnanchoredEventRepositoryPort unanchoredEventRepositoryPort;
    private final MerkleBatchRepositoryPort merkleBatchRepositoryPort;
    private final TransactionTemplate transactionTemplate;

    private final int maxStreamsPerBatch;
    private final int maxEventsPerBatch;
    private final int collectingRecoveryTimeoutSeconds;
    private final int collectingRecoveryMaxPerCycle;
    private final int collectingRecoveryWarnAfterAttempts;

    public BlockchainAnchorProducer(
            UnanchoredEventRepositoryPort unanchoredEventRepositoryPort,
            MerkleBatchRepositoryPort merkleBatchRepositoryPort,
            TransactionTemplate transactionTemplate,
            @Value("${traceability.anchor.producer.max-streams-per-batch:10}") int maxStreamsPerBatch,
            @Value("${traceability.anchor.producer.max-events-per-batch:1000}") int maxEventsPerBatch,
            @Value("${crypto.anchor.collecting-recovery.timeout-seconds:300}") int collectingRecoveryTimeoutSeconds,
            @Value("${crypto.anchor.collecting-recovery.max-per-cycle:10}") int collectingRecoveryMaxPerCycle,
            @Value("${crypto.anchor.collecting-recovery.warn-after-attempts:5}") int collectingRecoveryWarnAfterAttempts) {
        this.unanchoredEventRepositoryPort = unanchoredEventRepositoryPort;
        this.merkleBatchRepositoryPort = merkleBatchRepositoryPort;
        this.transactionTemplate = transactionTemplate;
        this.maxStreamsPerBatch = maxStreamsPerBatch;
        this.maxEventsPerBatch = maxEventsPerBatch;
        this.collectingRecoveryTimeoutSeconds = collectingRecoveryTimeoutSeconds;
        this.collectingRecoveryMaxPerCycle = collectingRecoveryMaxPerCycle;
        this.collectingRecoveryWarnAfterAttempts = collectingRecoveryWarnAfterAttempts;
    }

    /**
     * Periodically orchestrates the creation of a new MerkleBatch.
     * Fixed delay ensures we don't overlap executions on the same instance.
     *
     * Order within each cycle:
     * 1. FIRST: Recover abandoned COLLECTING batches (Phase 2+3 only, no re-claim).
     * 2. THEN: Claim new orphan events (Phase 1) and build new batch.
     */
    @Scheduled(fixedDelayString = "${traceability.anchor.producer.interval-ms:60000}")
    public void produceBatch() {
        log.info("Starting MerkleBatch production cycle...");

        // ── Recovery: abandoned COLLECTING batches ──────────────────────────
        recoverStaleCollectingBatches();

        // ── Normal flow: claim new orphans ──────────────────────────────────
        claimAndBuildNewBatch();
    }

    /**
     * Recovers batches stuck in COLLECTING state beyond the configured timeout.
     * Re-executes Phase 2 (recalculate MerkleTree) + Phase 3 (transition to PENDING)
     * without re-executing Phase 1 (no new event claiming, coverage unchanged).
     * Each batch is processed independently — a failure on one does not block others.
     */
    void recoverStaleCollectingBatches() {
        Instant cutoff = Instant.now().minusSeconds(collectingRecoveryTimeoutSeconds);
        List<MerkleBatch> staleBatches = merkleBatchRepositoryPort.findCollectingOlderThan(cutoff, collectingRecoveryMaxPerCycle);

        if (staleBatches.isEmpty()) {
            return;
        }

        log.info("Found {} stale COLLECTING batch(es) to recover", staleBatches.size());

        for (MerkleBatch batch : staleBatches) {
            try {
                recoverSingleBatch(batch);
            } catch (Exception e) {
                log.error("Failed to recover stale COLLECTING batch {}. Skipping to next batch.", batch.batchId(), e);
            }
        }
    }

    private void recoverSingleBatch(MerkleBatch batch) {
        String batchId = batch.batchId();

        // Atomically increment recoveryAttempts. If returns -1, batch is no longer COLLECTING.
        int attempts = merkleBatchRepositoryPort.incrementRecoveryAttempts(batchId);
        if (attempts < 0) {
            log.info("Recovery skip: Batch {} is no longer in COLLECTING state (already recovered by another worker).", batchId);
            return;
        }

        if (attempts > collectingRecoveryWarnAfterAttempts) {
            log.warn("Batch {} has been recovered {} times, exceeding threshold of {}. Investigate root cause.",
                    batchId, attempts, collectingRecoveryWarnAfterAttempts);
        }

        // Phase 2: Recalculate MerkleTree from the persisted coverage
        List<String> leafHashes = unanchoredEventRepositoryPort.getEventHashesByCoverage(batch.coverage());
        if (leafHashes.isEmpty()) {
            log.error("Recovery Phase 2 failed: No event hashes retrieved for batch {} coverage {}", batchId, batch.coverage());
            return;
        }

        MerkleTree tree = MerkleTree.build(leafHashes);
        String merkleRoot = tree.getRoot();
        log.info("Recovery Phase 2 completed for batch {}: MerkleTree root {} from {} leaves", batchId, merkleRoot, leafHashes.size());

        // Phase 3: Transition to PENDING
        boolean transitioned = merkleBatchRepositoryPort.transitionCollectingToPending(batchId, merkleRoot, leafHashes);
        if (transitioned) {
            log.info("Recovery Phase 3 completed: Batch {} successfully transitioned to PENDING.", batchId);
        } else {
            log.info("Recovery Phase 3 benign no-op: Batch {} was already transitioned by another instance.", batchId);
        }
    }

    private void claimAndBuildNewBatch() {
        String batchId = UUID.randomUUID().toString();
        
        // Fase 1: Reclamación Atómica (COLLECTING)
        Map<String, SequenceRange> coverage = transactionTemplate.execute(status -> {
            Map<String, SequenceRange> claimed = unanchoredEventRepositoryPort.claimOrphansAndAssignBatch(
                    batchId, maxStreamsPerBatch, maxEventsPerBatch);
            
            if (claimed.isEmpty()) {
                log.debug("No unanchored events found. Aborting batch production.");
                status.setRollbackOnly();
                return claimed;
            }

            MerkleBatch initialBatch = new MerkleBatch(
                    batchId,
                    claimed,
                    null,
                    null,
                    Instant.now(),
                    AnchorStatus.COLLECTING,
                    null, null, null, null, null, null, null, null, null, 0
            );
            
            merkleBatchRepositoryPort.save(initialBatch);
            log.info("Phase 1 completed: Created MerkleBatch(COLLECTING) {} with coverage {}", batchId, claimed);
            return claimed;
        });

        if (coverage == null || coverage.isEmpty()) {
            return;
        }

        try {
            // Fase 2: Construcción del Árbol
            List<String> leafHashes = unanchoredEventRepositoryPort.getEventHashesByCoverage(coverage);
            if (leafHashes.isEmpty()) {
                log.error("Phase 2 failed: No event hashes retrieved for coverage {}", coverage);
                return;
            }
            
            MerkleTree tree = MerkleTree.build(leafHashes);
            String merkleRoot = tree.getRoot();
            log.info("Phase 2 completed: Built MerkleTree with root {} from {} leaves", merkleRoot, leafHashes.size());

            // Fase 3: Transición a PENDING
            boolean transitioned = merkleBatchRepositoryPort.transitionCollectingToPending(batchId, merkleRoot, leafHashes);
            if (transitioned) {
                log.info("Phase 3 completed: Batch {} successfully transitioned to PENDING.", batchId);
            } else {
                log.info("Phase 3 benign no-op: Batch {} was already transitioned to PENDING by another instance.", batchId);
            }
        } catch (Exception e) {
            log.error("Error during Phase 2/3 of batch production for batch {}", batchId, e);
            // El batch queda en estado COLLECTING y puede ser reintentado o reclamado luego (fuera de alcance actual)
        }
    }
}

