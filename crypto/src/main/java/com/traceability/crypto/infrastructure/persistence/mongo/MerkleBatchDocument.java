package com.traceability.crypto.infrastructure.persistence.mongo;

import com.traceability.crypto.domain.AnchorStatus;
import com.traceability.crypto.domain.Resolution;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "merkle_batches")
public class MerkleBatchDocument {

    @Id
    private String id;
    
    @Indexed(unique = true)
    private String batchId;
    
    @Indexed
    private long sequenceRangeStart;
    
    private long sequenceRangeEnd;
    private String merkleRoot;
    private Instant createdAt;
    
    @Indexed
    private AnchorStatus status;
    
    private String network;
    private String smartContractAddress;
    private Long nonceUsed;
    private String transactionHash;
    private Instant submittedAt;
    private Instant anchoredAt;
    private Long confirmedBlockNumber;
    private Resolution resolution;

    // Getters and Setters

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getBatchId() {
        return batchId;
    }

    public void setBatchId(String batchId) {
        this.batchId = batchId;
    }

    public long getSequenceRangeStart() {
        return sequenceRangeStart;
    }

    public void setSequenceRangeStart(long sequenceRangeStart) {
        this.sequenceRangeStart = sequenceRangeStart;
    }

    public long getSequenceRangeEnd() {
        return sequenceRangeEnd;
    }

    public void setSequenceRangeEnd(long sequenceRangeEnd) {
        this.sequenceRangeEnd = sequenceRangeEnd;
    }

    public String getMerkleRoot() {
        return merkleRoot;
    }

    public void setMerkleRoot(String merkleRoot) {
        this.merkleRoot = merkleRoot;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public AnchorStatus getStatus() {
        return status;
    }

    public void setStatus(AnchorStatus status) {
        this.status = status;
    }

    public String getNetwork() {
        return network;
    }

    public void setNetwork(String network) {
        this.network = network;
    }

    public String getSmartContractAddress() {
        return smartContractAddress;
    }

    public void setSmartContractAddress(String smartContractAddress) {
        this.smartContractAddress = smartContractAddress;
    }

    public Long getNonceUsed() {
        return nonceUsed;
    }

    public void setNonceUsed(Long nonceUsed) {
        this.nonceUsed = nonceUsed;
    }

    public String getTransactionHash() {
        return transactionHash;
    }

    public void setTransactionHash(String transactionHash) {
        this.transactionHash = transactionHash;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(Instant submittedAt) {
        this.submittedAt = submittedAt;
    }

    public Instant getAnchoredAt() {
        return anchoredAt;
    }

    public void setAnchoredAt(Instant anchoredAt) {
        this.anchoredAt = anchoredAt;
    }

    public Long getConfirmedBlockNumber() {
        return confirmedBlockNumber;
    }

    public void setConfirmedBlockNumber(Long confirmedBlockNumber) {
        this.confirmedBlockNumber = confirmedBlockNumber;
    }

    public Resolution getResolution() {
        return resolution;
    }

    public void setResolution(Resolution resolution) {
        this.resolution = resolution;
    }
}
