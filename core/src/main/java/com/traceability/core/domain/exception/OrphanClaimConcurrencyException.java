package com.traceability.core.domain.exception;

public class OrphanClaimConcurrencyException extends RuntimeException {

    public OrphanClaimConcurrencyException(String batchId, int selectedSize, long modifiedCount) {
        super(String.format("Concurrency failure while claiming orphans for batch '%s': tried to claim %d but only modified %d", batchId, selectedSize, modifiedCount));
    }
}
