package com.traceability.core.domain.fund;

import com.traceability.core.domain.event.DomainEventPayload;
import com.traceability.core.domain.fund.exceptions.*;
import com.traceability.core.domain.fund.payloads.*;
import com.traceability.core.domain.shared.AggregateRoot;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Aggregate Root for Fund (Financial).
 * Ref: ADR-004, ADR-012, ADR-013, ADR-016
 */
public class Fund extends AggregateRoot {
    private String fundId;
    
    // Persisted state via replay
    private Long pledgedAmount;
    private long clearedAmount;
    private long pendingAllocationAmount;
    private long allocatedAmount;
    private long refundedAmount;

    // Transitory collections for sagas and idempotency
    private final Map<String, Long> activeAllocations = new HashMap<>();
    private final Set<String> processedAllocations = new HashSet<>();
    private final Set<String> processedRefunds = new HashSet<>();

    // Protected constructor for rehydration via AggregateRoot
    protected Fund() {}

    /**
     * Derived getter: Available amount for new allocations.
     */
    public long getAvailableAmount() {
        return clearedAmount - pendingAllocationAmount - allocatedAmount - refundedAmount;
    }
    
    // --- Genesis Commands (ADR-016 Dual Genesis) ---

    public static Fund registerFund(String fundId, Long pledgedAmount) {
        if (pledgedAmount != null && pledgedAmount <= 0) {
            throw new IllegalArgumentException("Pledged amount must be strictly positive if provided");
        }
        Fund fund = new Fund();
        fund.fundId = fundId; // Temporary setup, replay normally handles identity but for new aggregates we set it here or in apply.
        // Wait, normally `apply` should set the fundId. The payload doesn't contain fundId but usually the stream does.
        // I will set it here just in case, but rely on apply for the rest.
        
        fund.raiseEvent(FundEventType.FUND_REGISTERED, new FundRegisteredPayload(pledgedAmount));
        return fund;
    }

    public static Fund clearFundsGenesis(String fundId, long amount, String sourceRef) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Cleared amount must be strictly positive");
        }
        Fund fund = new Fund();
        fund.fundId = fundId;
        fund.raiseEvent(FundEventType.FUNDS_CLEARED, new FundsClearedPayload(amount, sourceRef));
        return fund;
    }

    // --- Regular Commands ---

    public void clearFunds(long amount, String sourceRef) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Cleared amount must be strictly positive");
        }
        raiseEvent(FundEventType.FUNDS_CLEARED, new FundsClearedPayload(amount, sourceRef));
    }

    public void requestAllocation(String allocationId, long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Requested allocation amount must be strictly positive");
        }
        if (processedAllocations.contains(allocationId)) {
            throw new DuplicateAllocationException("Allocation " + allocationId + " has already been requested/processed");
        }
        if (amount > getAvailableAmount()) {
            throw new InsufficientAvailableFundsException(
                String.format("Insufficient available funds for allocation %s. Requested: %d, Available: %d", 
                allocationId, amount, getAvailableAmount())
            );
        }
        raiseEvent(FundEventType.ALLOCATION_REQUESTED, new AllocationRequestedPayload(allocationId, amount));
    }

    public void confirmAllocation(String allocationId) {
        if (!activeAllocations.containsKey(allocationId)) {
            throw new InvalidFundTransitionException("Cannot confirm allocation " + allocationId + " because it is not active");
        }
        raiseEvent(FundEventType.ALLOCATION_CONFIRMED, new AllocationConfirmedPayload(allocationId));
    }

    public void reverseAllocation(String allocationId, String reason) {
        if (!activeAllocations.containsKey(allocationId)) {
            throw new InvalidFundTransitionException("Cannot reverse allocation " + allocationId + " because it is not active");
        }
        raiseEvent(FundEventType.ALLOCATION_REVERSED, new AllocationReversedPayload(allocationId, reason));
    }

    public void refund(String refundId, long refundAmount, String reason) {
        if (refundAmount <= 0) {
            throw new IllegalArgumentException("Refund amount must be strictly positive");
        }
        if (processedRefunds.contains(refundId)) {
            throw new DuplicateRefundException("Refund " + refundId + " has already been processed");
        }
        
        // Critical Rule (ADR-004): refundAmount + refundedAmount <= clearedAmount
        if (this.refundedAmount + refundAmount > this.clearedAmount) {
            throw new ExceedsClearedFundsException(
                String.format("Refund %s of amount %d exceeds total cleared funds %d (already refunded %d)", 
                refundId, refundAmount, this.clearedAmount, this.refundedAmount)
            );
        }
        
        boolean causedDeficit = refundAmount > getAvailableAmount();
        
        raiseEvent(FundEventType.FUNDS_REFUNDED, new FundsRefundedPayload(refundId, refundAmount, causedDeficit, reason));
    }

    // --- State Mutation ---

    @Override
    protected void apply(DomainEventPayload payload) {
        switch (payload) {
            case FundRegisteredPayload p -> {
                this.pledgedAmount = p.pledgedAmount();
            }
            case FundsClearedPayload p -> {
                this.clearedAmount += p.clearedAmount();
            }
            case AllocationRequestedPayload p -> {
                this.pendingAllocationAmount += p.requestedAmount();
                this.activeAllocations.put(p.allocationId(), p.requestedAmount());
                this.processedAllocations.add(p.allocationId());
            }
            case AllocationConfirmedPayload p -> {
                long originalAmount = this.activeAllocations.remove(p.allocationId());
                this.pendingAllocationAmount -= originalAmount;
                this.allocatedAmount += originalAmount;
            }
            case AllocationReversedPayload p -> {
                long originalAmount = this.activeAllocations.remove(p.allocationId());
                this.pendingAllocationAmount -= originalAmount;
            }
            case FundsRefundedPayload p -> {
                this.refundedAmount += p.refundAmount();
                this.processedRefunds.add(p.refundId());
            }
            default -> throw new IllegalArgumentException("Unknown payload type: " + payload.getClass());
        }
    }

    // Getters for testing
    public String getFundId() { return fundId; }
    public Long getPledgedAmount() { return pledgedAmount; }
    public long getClearedAmount() { return clearedAmount; }
    public long getPendingAllocationAmount() { return pendingAllocationAmount; }
    public long getAllocatedAmount() { return allocatedAmount; }
    public long getRefundedAmount() { return refundedAmount; }
    
    // Package-private setter for testing rehydration correctly if needed
    void setFundId(String fundId) { this.fundId = fundId; }
}
