package com.traceability.crypto.application.port.out;

import com.traceability.crypto.domain.AnchorStatus;
import com.traceability.crypto.domain.MerkleBatch;
import java.util.List;
import java.util.Optional;

public interface MerkleBatchRepositoryPort {
    
    MerkleBatch save(MerkleBatch batch);
    
    Optional<MerkleBatch> findByBatchId(String batchId);
    
    List<MerkleBatch> findByStatus(AnchorStatus status);
    
    java.util.stream.Stream<MerkleBatch> streamByStatus(AnchorStatus status);
    
    /**
     * Atomically transitions a batch from COLLECTING to PENDING and sets its merkleRoot.
     * Uses a conditional update (status = COLLECTING) to guarantee exactly-once transition.
     * @return true if transitioned, false if batch not found or not in COLLECTING state
     */
    boolean transitionCollectingToPending(String batchId, String merkleRoot, List<String> leafHashes);

    /**
     * Atomically claims the next PENDING batch by generating a nonce from the counter
     * and setting its status to SUBMITTING inside a single MongoDB transaction.
     * @param network the blockchain network identifier
     * @param smartContractAddress the address of the target smart contract
     * @return the claimed batch if one was found, empty otherwise
     */
    Optional<MerkleBatch> claimNextPendingBatchAndAssignNonce(String network, String smartContractAddress);
    
    /**
     * Finds batches stuck in COLLECTING state with createdAt older than the given cutoff.
     * Results are ordered by createdAt ASC (oldest first) and limited to the given count.
     * @param cutoff only batches created before this instant are returned
     * @param limit maximum number of batches to return
     * @return list of stale COLLECTING batches, oldest first
     */
    List<MerkleBatch> findCollectingOlderThan(java.time.Instant cutoff, int limit);

    /**
     * Atomically increments recoveryAttempts for a batch that is still in COLLECTING state.
     * Uses a conditional update (status = COLLECTING) so if the batch has already transitioned,
     * the increment is a no-op.
     * @param batchId the batch to increment
     * @return the new recoveryAttempts value after increment, or -1 if the batch is no longer COLLECTING
     */
    int incrementRecoveryAttempts(String batchId);

    /**
     * Seeds the nonce counter during startup reconciliation if the on-chain nonce
     * is higher than the currently persisted nonce.
     */
    void seedNonceCounter(String network, String smartContractAddress, long startingNonce);
}
