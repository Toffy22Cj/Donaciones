package com.traceability.crypto.domain.exception;

public class BlockchainAnchorTimeoutException extends RuntimeException {
    public BlockchainAnchorTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
