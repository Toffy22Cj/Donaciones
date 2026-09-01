package com.traceability.crypto.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.traceability.crypto.domain.MerkleBatch;

public interface BlockchainAnchorRepositoryPort extends MerkleBatchRepositoryPort {
    /**
     * Atomically claims the next PENDING batch by generating a nonce from the counter
     * and setting its status to SUBMITTING inside a single MongoDB transaction.
     * Retries automatically if a TransientTransactionError occurs under high contention.
     *
     * @param network the blockchain network identifier
     * @param smartContractAddress the address of the target smart contract
     * @return the claimed batch if one was found, empty otherwise
     */
    Optional<MerkleBatch> claimNextPendingBatchAndAssignNonceWithRetry(String network, String smartContractAddress);

    Optional<MerkleBatch> findSubmittingWithoutTxHashAndNonce();

    List<MerkleBatch> findSubmittingWithoutTxHashOlderThan(Instant cutoff);

    List<MerkleBatch> findSubmittedOlderFirst();

    void keepSubmittingWithSameNonce(String batchId);

    void reconcileSubmittingTimeout(String batchId, Long nonceUsed);

    void updateSubmitted(String batchId, String txHash, Instant submittedAt);

    void markStuck(String batchId);

    void markFailed(String batchId);

    void markAnchored(String batchId, Long confirmedBlockNumber, Instant anchoredAt);

    void markAnchorMismatch(String batchId);
}
