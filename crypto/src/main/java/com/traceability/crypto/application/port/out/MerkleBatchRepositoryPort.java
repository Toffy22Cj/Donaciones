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
     * Seeds the nonce counter during startup reconciliation if the on-chain nonce
     * is higher than the currently persisted nonce.
     */
    void seedNonceCounter(String network, String smartContractAddress, long startingNonce);
}
