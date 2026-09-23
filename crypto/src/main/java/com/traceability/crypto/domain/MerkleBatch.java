package com.traceability.crypto.domain;

import java.time.Instant;

/**
 * Represents a batch of events anchored using a Merkle Tree.
 */
public record MerkleBatch(
    String batchId,
    java.util.Map<String, com.traceability.contracts.SequenceRange> coverage,
    String merkleRoot,
    java.util.List<String> leafHashes,
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
    java.math.BigInteger maxFeePerGasOverride,
    int recoveryAttempts
) {
    public MerkleBatch(String batchId, java.util.Map<String, com.traceability.contracts.SequenceRange> coverage, String merkleRoot, Instant createdAt, AnchorStatus status, String network, String smartContractAddress, Long nonceUsed, String transactionHash, Instant submittedAt, Instant anchoredAt, Long confirmedBlockNumber, Resolution resolution) {
        this(batchId, coverage, merkleRoot, null, createdAt, status, network, smartContractAddress, nonceUsed, transactionHash, submittedAt, anchoredAt, confirmedBlockNumber, resolution, null, 0);
    }

    public MerkleBatch(String batchId, java.util.Map<String, com.traceability.contracts.SequenceRange> coverage, String merkleRoot, java.util.List<String> leafHashes, Instant createdAt, AnchorStatus status, String network, String smartContractAddress, Long nonceUsed, String transactionHash, Instant submittedAt, Instant anchoredAt, Long confirmedBlockNumber, Resolution resolution) {
        this(batchId, coverage, merkleRoot, leafHashes, createdAt, status, network, smartContractAddress, nonceUsed, transactionHash, submittedAt, anchoredAt, confirmedBlockNumber, resolution, null, 0);
    }

    public MerkleBatch(String batchId, java.util.Map<String, com.traceability.contracts.SequenceRange> coverage, String merkleRoot, java.util.List<String> leafHashes, Instant createdAt, AnchorStatus status, String network, String smartContractAddress, Long nonceUsed, String transactionHash, Instant submittedAt, Instant anchoredAt, Long confirmedBlockNumber, Resolution resolution, java.math.BigInteger maxFeePerGasOverride) {
        this(batchId, coverage, merkleRoot, leafHashes, createdAt, status, network, smartContractAddress, nonceUsed, transactionHash, submittedAt, anchoredAt, confirmedBlockNumber, resolution, maxFeePerGasOverride, 0);
    }
}
