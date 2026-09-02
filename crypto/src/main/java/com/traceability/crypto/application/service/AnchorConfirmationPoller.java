package com.traceability.crypto.application.service;

import com.traceability.crypto.application.port.out.BlockchainAnchorRepositoryPort;
import com.traceability.crypto.application.port.out.BlockchainTransactionReceiptPort;
import com.traceability.crypto.domain.MerkleBatch;
import com.traceability.crypto.domain.TransactionConfirmation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.web3j.utils.Numeric;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Service
public class AnchorConfirmationPoller {

    private static final Logger log = LoggerFactory.getLogger(AnchorConfirmationPoller.class);

    private final BlockchainAnchorRepositoryPort repositoryPort;
    private final BlockchainTransactionReceiptPort receiptPort;

    @Value("${crypto.anchor.confirmations-required:12}")
    private long confirmationsRequired;

    @Value("${crypto.anchor.receipt-timeout-seconds:3600}")
    private long receiptTimeoutSeconds;

    public AnchorConfirmationPoller(BlockchainAnchorRepositoryPort repositoryPort,
                                    BlockchainTransactionReceiptPort receiptPort) {
        this.repositoryPort = repositoryPort;
        this.receiptPort = receiptPort;
    }

    @Scheduled(fixedDelayString = "${crypto.anchor.poll.delay:15000}")
    public void pollSubmittedBatches() {
        try {
            List<MerkleBatch> submittedBatches = repositoryPort.findSubmittedOlderFirst();
            if (submittedBatches.isEmpty()) {
                return;
            }

            Long currentBlockNumber = null;

            for (MerkleBatch batch : submittedBatches) {
                try {
                    // Check if it has timed out waiting for ANY receipt
                    if (hasTimedOut(batch)) {
                        log.error("Batch {} has timed out waiting for a receipt. Marking as STUCK.", batch.batchId());
                        repositoryPort.markStuck(batch.batchId());
                        continue;
                    }

                    Optional<TransactionConfirmation> optionalConfirmation = 
                            receiptPort.getTransactionConfirmation(batch.transactionHash(), batch.smartContractAddress());
                    
                    if (optionalConfirmation.isEmpty()) {
                        // Still pending, no receipt found yet.
                        continue;
                    }

                    TransactionConfirmation confirmation = optionalConfirmation.get();

                    // Scenario: receipt.status == 0 -> FAILED
                    if (confirmation.isReverted()) {
                        log.warn("Batch {} transaction {} reverted on-chain. Marking as FAILED.", batch.batchId(), batch.transactionHash());
                        repositoryPort.markFailed(batch.batchId());
                        continue;
                    }

                    // For remaining scenarios, we need the current block number to check confirmations
                    if (currentBlockNumber == null) {
                        currentBlockNumber = receiptPort.getCurrentBlockNumber();
                    }

                    long confirmations = currentBlockNumber - confirmation.blockNumber();
                    
                    // Scenario: insufficient confirmations -> DO NOTHING YET
                    if (confirmations < confirmationsRequired) {
                        log.debug("Batch {} has {} confirmations, waiting for {}.", batch.batchId(), confirmations, confirmationsRequired);
                        continue;
                    }

                    // Verify the emitted root matches EXACTLY what we expected
                    byte[] expectedRootBytes = Numeric.hexStringToByteArray(batch.merkleRoot());
                    byte[] actualRootBytes = confirmation.anchoredRootBytes();

                    if (actualRootBytes == null || !Arrays.equals(expectedRootBytes, actualRootBytes)) {
                        // Scenario: receipt.status == 1 but root does NOT match -> ANCHOR_MISMATCH
                        log.error("Batch {} anchored root does not match the batch merkleRoot! Marking as ANCHOR_MISMATCH.", batch.batchId());
                        repositoryPort.markAnchorMismatch(batch.batchId());
                    } else {
                        // Scenario: receipt.status == 1 + sufficient confirmations + exact root match -> ANCHORED
                        log.info("Batch {} is fully confirmed and ANCHORED at block {}.", batch.batchId(), confirmation.blockNumber());
                        repositoryPort.markAnchored(batch.batchId(), confirmation.blockNumber(), Instant.now());
                    }

                } catch (Exception e) {
                    log.error("Failed to process confirmation for batch {}. Skipping this cycle.", batch.batchId(), e);
                }
            }
        } catch (Exception e) {
            log.error("CRITICAL: Unforeseen error in AnchorConfirmationPoller.", e);
        }
    }

    private boolean hasTimedOut(MerkleBatch batch) {
        if (batch.submittedAt() == null) {
            return false;
        }
        Instant cutoff = Instant.now().minusSeconds(receiptTimeoutSeconds);
        return batch.submittedAt().isBefore(cutoff);
    }
}
