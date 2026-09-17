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
    
    // Identity & Metadata (ADR-016 Genesis attributes)
    private String currency;
    private String campaignRef;
    private String donorRef;
    private OrganizationRef organizationRef;
    
    // Persisted state via replay
    private Long pledgedAmount;
    private long clearedAmount;
    private long pendingAllocationAmount;
    private long allocatedAmount;
    private long refundedAmount;

    // Transitory collections for sagas and idempotency
    private final Map<String, Long> activeAllocations = new HashMap<>();
    private final Map<String, AllocationStatus> allocationStatus = new HashMap<>();
    private final Set<String> processedRefunds = new HashSet<>();

    // Protected constructor for rehydration via AggregateRoot
    protected Fund() {}

    /**
     * Derived getter: Available amount for new allocations.
     */
    public long getAvailableAmount() {
        return clearedAmount - pendingAllocationAmount - allocatedAmount - refundedAmount;
    }
    
    /**
     * Derived getter: Status (Section 6.2).
     */
    public FundStatus getStatus() {
        if (clearedAmount > 0) {
            if (refundedAmount >= clearedAmount) {
                return FundStatus.FULLY_REFUNDED;
            } else if (refundedAmount > 0) {
                return FundStatus.REFUNDED;
            } else {
                return FundStatus.CLEARED;
            }
        }
        if (pledgedAmount != null && pledgedAmount > 0) {
            return FundStatus.PLEDGED;
        }
        return FundStatus.FAILED; // Or another default if we introduce explicit failures later
    }
    
    // --- Rehydration ---
    
    public static Fund rehydrate(String fundId, Iterable<DomainEventPayload> historicalPayloads, long currentVersion) {
        Fund fund = new Fund();
        fund.fundId = fundId;
        fund.replay(historicalPayloads, currentVersion);
        return fund;
    }

    // --- Genesis Commands (ADR-016 Dual Genesis) ---

    public static Fund registerFund(String fundId, OrganizationRef organizationRef, Long pledgedAmount, String currency, String campaignRef, String donorRef) {
        if (organizationRef == null) {
            throw new InvalidFundGenesisException("OrganizationRef is mandatory for fund genesis");
        }
        if (pledgedAmount != null && pledgedAmount <= 0) {
            throw new InvalidFundGenesisException("Pledged amount must be strictly positive if provided");
        }
        Fund fund = new Fund();
        fund.fundId = fundId; // Temporary setup, replay normally handles identity but for new aggregates we set it here or in apply.
        // Wait, normally `apply` should set the fundId. The payload doesn't contain fundId but usually the stream does.
        // I will set it here just in case, but rely on apply for the rest.
        
        fund.raiseEvent(FundEventType.FUND_REGISTERED, new FundRegisteredV2Payload(organizationRef.value(), pledgedAmount, currency, campaignRef, donorRef));
        return fund;
    }

    public static Fund clearFundsGenesis(String fundId, OrganizationRef organizationRef, long amount, String sourceRef, String currency, String campaignRef, String donorRef) {
        if (organizationRef == null) {
            throw new InvalidFundGenesisException("OrganizationRef is mandatory for fund genesis");
        }
        if (amount <= 0) {
            throw new InvalidFundGenesisException("Cleared amount must be strictly positive");
        }
        Fund fund = new Fund();
        fund.fundId = fundId;
        fund.raiseEvent(FundEventType.FUNDS_CLEARED, new FundsClearedV2Payload(organizationRef.value(), amount, sourceRef, currency, campaignRef, donorRef));
        return fund;
    }

    // --- Regular Commands ---

    private void checkOrganizationAssigned() {
        if (this.organizationRef == null) {
            throw new FundNotAssociatedToOrganizationException("Fund " + fundId + " is not associated to any organization");
        }
    }

    public void clearFunds(long amount, String sourceRef) {
        checkOrganizationAssigned();
        if (amount <= 0) {
            throw new IllegalArgumentException("Cleared amount must be strictly positive");
        }
        // Retain existing genesis attributes when clearing more funds
        raiseEvent(FundEventType.FUNDS_CLEARED, new FundsClearedV2Payload(this.organizationRef.value(), amount, sourceRef, this.currency, this.campaignRef, this.donorRef));
    }

    public void requestAllocation(String allocationId, long amount) {
        checkOrganizationAssigned();
        if (amount <= 0) {
            throw new IllegalArgumentException("Requested allocation amount must be strictly positive");
        }
        if (allocationStatus.containsKey(allocationId)) {
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
        checkOrganizationAssigned();
        if (allocationStatus.get(allocationId) == AllocationStatus.CONFIRMED) {
            throw new RedundantAllocationConfirmationException("Allocation " + allocationId + " is already confirmed");
        }
        if (!activeAllocations.containsKey(allocationId)) {
            throw new InvalidFundTransitionException("Cannot confirm allocation " + allocationId + " because it is not active");
        }
        raiseEvent(FundEventType.ALLOCATION_CONFIRMED, new AllocationConfirmedPayload(allocationId));
    }

    public void reverseAllocation(String allocationId, String reason) {
        checkOrganizationAssigned();
        if (allocationStatus.get(allocationId) == AllocationStatus.REVERSED) {
            throw new RedundantAllocationReversalException("Allocation " + allocationId + " is already reversed");
        }
        if (!activeAllocations.containsKey(allocationId)) {
            throw new InvalidFundTransitionException("Cannot reverse allocation " + allocationId + " because it is not active");
        }
        raiseEvent(FundEventType.ALLOCATION_REVERSED, new AllocationReversedPayload(allocationId, reason));
    }

    public void refund(String refundId, long refundAmount, String reason) {
        checkOrganizationAssigned();
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
            case FundRegisteredV2Payload p -> {
                if (p.organizationRef() != null) this.organizationRef = new OrganizationRef(p.organizationRef());
                this.pledgedAmount = p.pledgedAmount();
                if (p.currency() != null) this.currency = p.currency();
                if (p.campaignRef() != null) this.campaignRef = p.campaignRef();
                if (p.donorRef() != null) this.donorRef = p.donorRef();
            }
            case FundsClearedV2Payload p -> {
                if (p.organizationRef() != null && this.organizationRef == null) this.organizationRef = new OrganizationRef(p.organizationRef());
                this.clearedAmount += p.clearedAmount();
                if (p.currency() != null && this.currency == null) this.currency = p.currency();
                if (p.campaignRef() != null && this.campaignRef == null) this.campaignRef = p.campaignRef();
                if (p.donorRef() != null && this.donorRef == null) this.donorRef = p.donorRef();
            }
            case FundRegisteredPayload p -> {
                this.pledgedAmount = p.pledgedAmount();
                if (p.currency() != null) this.currency = p.currency();
                if (p.campaignRef() != null) this.campaignRef = p.campaignRef();
                if (p.donorRef() != null) this.donorRef = p.donorRef();
            }
            case FundsClearedPayload p -> {
                this.clearedAmount += p.clearedAmount();
                if (p.currency() != null && this.currency == null) this.currency = p.currency();
                if (p.campaignRef() != null && this.campaignRef == null) this.campaignRef = p.campaignRef();
                if (p.donorRef() != null && this.donorRef == null) this.donorRef = p.donorRef();
            }
            case AllocationRequestedPayload p -> {
                this.pendingAllocationAmount += p.requestedAmount();
                this.activeAllocations.put(p.allocationId(), p.requestedAmount());
                this.allocationStatus.put(p.allocationId(), AllocationStatus.REQUESTED);
            }
            case AllocationConfirmedPayload p -> {
                long originalAmount = this.activeAllocations.remove(p.allocationId());
                this.pendingAllocationAmount -= originalAmount;
                this.allocatedAmount += originalAmount;
                this.allocationStatus.put(p.allocationId(), AllocationStatus.CONFIRMED);
            }
            case AllocationReversedPayload p -> {
                long originalAmount = this.activeAllocations.remove(p.allocationId());
                this.pendingAllocationAmount -= originalAmount;
                this.allocationStatus.put(p.allocationId(), AllocationStatus.REVERSED);
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
    public String getCurrency() { return currency; }
    public String getCampaignRef() { return campaignRef; }
    public String getDonorRef() { return donorRef; }
    public OrganizationRef getOrganizationRef() { return organizationRef; }
    public Long getPledgedAmount() { return pledgedAmount; }
    public long getClearedAmount() { return clearedAmount; }
    public long getPendingAllocationAmount() { return pendingAllocationAmount; }
    public long getAllocatedAmount() { return allocatedAmount; }
    public long getRefundedAmount() { return refundedAmount; }
    
    // Package-private setter for testing rehydration correctly if needed
    void setFundId(String fundId) { this.fundId = fundId; }
}
