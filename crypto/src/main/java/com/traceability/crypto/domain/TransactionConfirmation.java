package com.traceability.crypto.domain;

/**
 * Represents the on-chain confirmation details of an anchoring transaction.
 */
public record TransactionConfirmation(
    boolean isReverted,
    Long blockNumber,
    byte[] anchoredRootBytes // The actual bytes32 emitted by the RootStored event, or null if not found
) {}
