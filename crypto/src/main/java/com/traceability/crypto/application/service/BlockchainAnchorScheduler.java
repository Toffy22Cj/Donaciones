package com.traceability.crypto.application.service;

import com.traceability.crypto.application.port.out.BlockchainAnchorRepositoryPort;
import com.traceability.crypto.application.port.out.BlockchainAnchorSubmitterPort;
import com.traceability.crypto.domain.MerkleBatch;
import com.traceability.crypto.domain.exception.BlockchainAnchorTimeoutException;
import com.traceability.crypto.domain.exception.BlockchainNodeCommunicationException;
import com.traceability.crypto.domain.exception.GasCapExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class BlockchainAnchorScheduler {

    private static final Logger log = LoggerFactory.getLogger(BlockchainAnchorScheduler.class);

    private final BlockchainAnchorRepositoryPort repositoryPort;
    private final BlockchainAnchorSubmitterPort submitterPort;

    @Value("${crypto.anchor.network:polygon-mumbai}")
    private String network;

    @Value("${crypto.anchor.smart-contract:0x0000000000000000000000000000000000000000}")
    private String smartContractAddress;

    @Value("${crypto.anchor.stuck-timeout-seconds:3600}")
    private int stuckTimeoutSeconds;

    public BlockchainAnchorScheduler(BlockchainAnchorRepositoryPort repositoryPort,
                                     BlockchainAnchorSubmitterPort submitterPort) {
        this.repositoryPort = repositoryPort;
        this.submitterPort = submitterPort;
    }

    /**
     * Main Scheduler Flow. 
     * Enforces Priority Guard and handles transaction submission.
     */
    @Scheduled(fixedDelayString = "${crypto.anchor.submit.delay:30000}")
    public void processNextBatch() {
        try {
            // 1. PRIORITY GUARD: Check for stuck SUBMITTING batches without txHash FIRST
            Optional<MerkleBatch> submittingBatch = repositoryPort.findSubmittingWithoutTxHashAndNonce();
            
            MerkleBatch batchToProcess;
            if (submittingBatch.isPresent()) {
                batchToProcess = submittingBatch.get();
                log.info("Priority Guard Triggered: Found stuck SUBMITTING batch {} with nonce {}. Retrying before claiming any PENDING.", 
                         batchToProcess.batchId(), batchToProcess.nonceUsed());
            } else {
                // 2. Only if no SUBMITTING batch is stuck, we claim a new PENDING batch
                Optional<MerkleBatch> newBatch = repositoryPort.claimNextPendingBatchAndAssignNonceWithRetry(network, smartContractAddress);
                if (newBatch.isEmpty()) {
                    log.debug("No PENDING batches to process");
                    return;
                }
                batchToProcess = newBatch.get();
                log.info("Claimed new PENDING batch {} and assigned nonce {}", batchToProcess.batchId(), batchToProcess.nonceUsed());
            }

            // 3. Delegate to the Submitter Port
            try {
                String txHash = submitterPort.submitBatch(batchToProcess);
                
                // 4. On SUCCESS, update the state to SUBMITTED
                repositoryPort.updateSubmitted(batchToProcess.batchId(), txHash, Instant.now());
                log.info("Batch {} successfully submitted to network with txHash {}", batchToProcess.batchId(), txHash);

            } catch (GasCapExceededException | BlockchainNodeCommunicationException e) {
                // DETERMINISTIC FAILURE: We know nothing reached the network
                log.warn("Deterministic submission failure for batch {}: {}", batchToProcess.batchId(), e.getMessage());
                repositoryPort.keepSubmittingWithSameNonce(batchToProcess.batchId());
            } catch (BlockchainAnchorTimeoutException e) {
                // AMBIGUOUS FAILURE: Transaction might be in the mempool
                log.warn("Ambiguous timeout during submission for batch {}. State requires reconciliation.", batchToProcess.batchId(), e);
                repositoryPort.reconcileSubmittingTimeout(batchToProcess.batchId(), batchToProcess.nonceUsed());
            }

        } catch (Exception e) {
            // 5. UNFORESEEN EXCEPTION CATCH-ALL
            // Log with high severity, but do NOT propagate out of the @Scheduled method.
            // If the error occurred after a batch was claimed or picked up by the priority guard,
            // the batch remains safely in SUBMITTING and will be picked up in the next cycle.
            // If the error occurred before (e.g. Mongo connection issue), no batch was affected.
            log.error("CRITICAL: Unforeseen error in BlockchainAnchorScheduler. Batch state left unchanged.", e);
        }
    }

    /**
     * Separate mechanism to monitor genuinely STUCK batches that have been in SUBMITTING
     * for too long, alerting operations but NOT auto-resolving them.
     */
    @Scheduled(fixedDelayString = "${crypto.anchor.stuck-monitor.delay:300000}") // e.g. every 5 mins
    public void checkStuckSubmittingBatches() {
        Instant cutoff = Instant.now().minusSeconds(stuckTimeoutSeconds);
        
        // Note: this finds batches that were created (and transitioned to SUBMITTING) older than cutoff
        // A more precise query might look at a specific submittingAt timestamp if added, 
        // but olderThan(cutoff) serves the semantic purpose for alerting.
        List<MerkleBatch> stuckBatches = repositoryPort.findSubmittingWithoutTxHashOlderThan(cutoff);
        
        for (MerkleBatch batch : stuckBatches) {
            log.error("OPERATIONAL ALERT: Batch {} has been STUCK in SUBMITTING since {}. Manual intervention required.", 
                      batch.batchId(), batch.createdAt()); // Or a dedicated submittingAt field
            repositoryPort.markStuck(batch.batchId());
            emitOperationalAlert(batch);
        }
    }

    private void emitOperationalAlert(MerkleBatch batch) {
        // In a real system, this could publish to an SNS topic, PagerDuty, Slack, etc.
        log.error("=> ALERT EMITTED: Batch {} is permanently STUCK", batch.batchId());
    }
}
