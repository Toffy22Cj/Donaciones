package com.traceability.core.domain.fund;

import com.traceability.core.domain.event.DomainEventPayload;
import com.traceability.core.domain.fund.exceptions.*;
import com.traceability.core.domain.fund.payloads.FundRegisteredPayload;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class FundTest {

    private final OrganizationRef orgRef = new OrganizationRef("ORG-1");

    @Test
    void testDualGenesis_RegisterThenClear() {
        // Genesis 1
        Fund fund = Fund.registerFund("F1", orgRef, 1000L, "COP", "CAMPAIGN-1", "DONOR-A");
        assertEquals("F1", fund.getFundId());
        assertEquals("ORG-1", fund.getOrganizationRef().value());
        assertEquals(1000L, fund.getPledgedAmount());
        assertEquals(0L, fund.getClearedAmount());
        assertEquals(0L, fund.getAvailableAmount());

        fund.clearFunds(500L, "TX-001");
        assertEquals(500L, fund.getClearedAmount());
        assertEquals(500L, fund.getAvailableAmount());
    }

    @Test
    void testDualGenesis_DirectClear() {
        // Genesis 2
        Fund fund = Fund.clearFundsGenesis("F2", orgRef, 800L, "TX-002", "COP", "CAMPAIGN-1", "DONOR-A");
        assertEquals("F2", fund.getFundId());
        assertEquals("ORG-1", fund.getOrganizationRef().value());
        assertNull(fund.getPledgedAmount());
        assertEquals(800L, fund.getClearedAmount());
        assertEquals(800L, fund.getAvailableAmount());
    }

    @Test
    void testSagaHappyPath_RequestAndConfirm() {
        Fund fund = Fund.clearFundsGenesis("F1", orgRef, 1000L, "TX", "COP", "CAMPAIGN-1", "DONOR-A");
        
        fund.requestAllocation("ALLOC-1", 400L);
        assertEquals(600L, fund.getAvailableAmount());
        assertEquals(400L, fund.getPendingAllocationAmount());
        assertEquals(0L, fund.getAllocatedAmount());

        fund.confirmAllocation("ALLOC-1");
        assertEquals(600L, fund.getAvailableAmount());
        assertEquals(0L, fund.getPendingAllocationAmount());
        assertEquals(400L, fund.getAllocatedAmount());
    }

    @Test
    void testSagaFallback_RequestAndReverse() {
        Fund fund = Fund.clearFundsGenesis("F1", orgRef, 1000L, "TX", "COP", "CAMPAIGN-1", "DONOR-A");
        
        fund.requestAllocation("ALLOC-1", 300L);
        assertEquals(700L, fund.getAvailableAmount());
        assertEquals(300L, fund.getPendingAllocationAmount());

        fund.reverseAllocation("ALLOC-1", "Timeout");
        assertEquals(1000L, fund.getAvailableAmount()); // Funds restored
        assertEquals(0L, fund.getPendingAllocationAmount());
        assertEquals(0L, fund.getAllocatedAmount());
    }

    @Test
    void testOverdraftInvariant_ADR004() {
        Fund fund = Fund.clearFundsGenesis("F1", orgRef, 1000L, "TX", "COP", "CAMPAIGN-1", "DONOR-A");
        
        // Allocate 800, so available is 200
        fund.requestAllocation("ALLOC-1", 800L);
        fund.confirmAllocation("ALLOC-1");
        assertEquals(200L, fund.getAvailableAmount());

        // Refund 300. This is > available (200), so it causes deficit, but it is <= cleared (1000)
        assertDoesNotThrow(() -> fund.refund("REF-1", 300L, "Overdraft allowed"));
        assertEquals(300L, fund.getRefundedAmount());
        assertEquals(-100L, fund.getAvailableAmount()); // Deficit

        // Now cleared = 1000. Refunded = 300. Max remaining allowed to refund = 700.
        // Try to refund 800 -> should throw ExceedsClearedFundsException
        assertThrows(ExceedsClearedFundsException.class, () -> fund.refund("REF-2", 800L, "Too much"));
    }

    @Test
    void testIdempotence_DuplicateAllocationAndRefund() {
        Fund fund = Fund.clearFundsGenesis("F1", orgRef, 1000L, "TX", "COP", "CAMPAIGN-1", "DONOR-A");
        
        fund.requestAllocation("ALLOC-1", 100L);
        assertThrows(DuplicateAllocationException.class, () -> fund.requestAllocation("ALLOC-1", 100L));

        fund.refund("REF-1", 50L, "Refund 1");
        assertThrows(DuplicateRefundException.class, () -> fund.refund("REF-1", 50L, "Refund 1 again"));
    }

    @Test
    void testInsufficientFundsForAllocation() {
        Fund fund = Fund.clearFundsGenesis("F1", orgRef, 100L, "TX", "COP", "CAMPAIGN-1", "DONOR-A");
        assertThrows(InsufficientAvailableFundsException.class, () -> fund.requestAllocation("ALLOC-1", 150L));
    }
    
    @Test
    void testInvalidTransitions() {
        Fund fund = Fund.clearFundsGenesis("F1", orgRef, 1000L, "TX", "COP", "CAMPAIGN-1", "DONOR-A");
        assertThrows(InvalidFundTransitionException.class, () -> fund.confirmAllocation("NON_EXISTENT"));
        assertThrows(InvalidFundTransitionException.class, () -> fund.reverseAllocation("NON_EXISTENT", "Reason"));
    }

    @Test
    void testRedundantConfirmAndReverse() {
        Fund fund = Fund.clearFundsGenesis("F1", orgRef, 1000L, "TX", "COP", "CAMPAIGN-1", "DONOR-A");
        
        fund.requestAllocation("ALLOC-1", 100L);
        fund.confirmAllocation("ALLOC-1");
        assertThrows(RedundantAllocationConfirmationException.class, () -> fund.confirmAllocation("ALLOC-1"));
        
        fund.requestAllocation("ALLOC-2", 100L);
        fund.reverseAllocation("ALLOC-2", "Cancelled");
        assertThrows(RedundantAllocationReversalException.class, () -> fund.reverseAllocation("ALLOC-2", "Cancelled"));
    }

    @Test
    void testGenesisWithoutOrganizationRefThrowsException() {
        assertThrows(InvalidFundGenesisException.class, () -> Fund.registerFund("F1", null, 100L, "COP", "CAMP-1", "DONOR-1"));
        assertThrows(InvalidFundGenesisException.class, () -> Fund.clearFundsGenesis("F1", null, 100L, "TX-1", "COP", "CAMP-1", "DONOR-1"));
    }

    @Test
    void testV1EventUpcastingPreventsWriteCommands() {
        // Create an original v1 payload (without organizationRef)
        DomainEventPayload v1Payload = new FundRegisteredPayload(100L, "COP", "CAMP-1", "DONOR-1");
        
        // Rehydrate fund with the v1 payload
        Fund fund = Fund.rehydrate("F1", Collections.singletonList(v1Payload), 1);
        
        // Assert that the organizationRef is null and state is correct
        assertNull(fund.getOrganizationRef());
        assertEquals(100L, fund.getPledgedAmount());
        
        // Ensure any write command fails with FundNotAssociatedToOrganizationException
        assertThrows(FundNotAssociatedToOrganizationException.class, () -> fund.requestAllocation("ALLOC-1", 50L));
        
        // Confirm no uncommitted events were added
        assertTrue(fund.getUncommittedEvents().isEmpty());
    }
}
