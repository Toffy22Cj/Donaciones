package com.traceability.crypto.domain.exception;

/**
 * Thrown when a deterministic node communication error occurs BEFORE 
 * any transaction is transmitted to the network. This guarantees no state mutation 
 * has occurred on-chain or in the mempool, making it safe for immediate direct retry.
 */
public class BlockchainNodeCommunicationException extends RuntimeException {
    public BlockchainNodeCommunicationException(String message, Throwable cause) {
        super(message, cause);
    }
}
