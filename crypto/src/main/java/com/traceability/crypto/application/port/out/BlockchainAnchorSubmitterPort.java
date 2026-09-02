package com.traceability.crypto.application.port.out;

import com.traceability.crypto.domain.MerkleBatch;

public interface BlockchainAnchorSubmitterPort {
    /**
     * Submits a MerkleBatch that has already been claimed and has an assigned nonce.
     * Returns the transaction hash.
     */
    String submitBatch(MerkleBatch batch);
}
