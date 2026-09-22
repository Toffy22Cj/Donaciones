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

    public BlockchainAnchorProducer(
            UnanchoredEventRepositoryPort unanchoredEventRepositoryPort,
            MerkleBatchRepositoryPort merkleBatchRepositoryPort,
            TransactionTemplate transactionTemplate,
            @Value("${traceability.anchor.producer.max-streams-per-batch:10}") int maxStreamsPerBatch,
            @Value("${traceability.anchor.producer.max-events-per-batch:1000}") int maxEventsPerBatch) {
        this.unanchoredEventRepositoryPort = unanchoredEventRepositoryPort;
        this.merkleBatchRepositoryPort = merkleBatchRepositoryPort;
        this.transactionTemplate = transactionTemplate;
        this.maxStreamsPerBatch = maxStreamsPerBatch;
        this.maxEventsPerBatch = maxEventsPerBatch;
    }

    /**
     * Periodically orchestrates the creation of a new MerkleBatch.
     * Fixed delay ensures we don't overlap executions on the same instance.
     */
    @Scheduled(fixedDelayString = "${traceability.anchor.producer.interval-ms:60000}")
    public void produceBatch() {
        log.info("Starting MerkleBatch production cycle...");
        
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
                    null, null, null, null, null, null, null, null, null
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
