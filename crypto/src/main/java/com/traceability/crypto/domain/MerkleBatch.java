package com.traceability.crypto.domain;

import java.time.Instant;

/**
 * Represents a batch of events anchored using a Merkle Tree.
 */
public record MerkleBatch(
    String batchId,
    long sequenceRangeStart,
    long sequenceRangeEnd,
    String merkleRoot,
    Instant createdAt,
    AnchorStatus status,
    String network,
    String smartContractAddress,
    Long nonceUsed,
    String transactionHash,
    Instant submittedAt,
    Instant anchoredAt,
    Long confirmedBlockNumber,
    Resolution resolution,
    java.math.BigInteger maxFeePerGasOverride
) {
    public MerkleBatch(String batchId, long sequenceRangeStart, long sequenceRangeEnd, String merkleRoot, Instant createdAt, AnchorStatus status, String network, String smartContractAddress, Long nonceUsed, String transactionHash, Instant submittedAt, Instant anchoredAt, Long confirmedBlockNumber, Resolution resolution) {
        this(batchId, sequenceRangeStart, sequenceRangeEnd, merkleRoot, createdAt, status, network, smartContractAddress, nonceUsed, transactionHash, submittedAt, anchoredAt, confirmedBlockNumber, resolution, null);
    }
}
