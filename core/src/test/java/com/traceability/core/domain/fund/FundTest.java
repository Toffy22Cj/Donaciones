package com.traceability.core.domain.fund;

import com.traceability.core.domain.fund.exceptions.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FundTest {

    @Test
    void testDualGenesis_RegisterThenClear() {
        // Genesis 1
        Fund fund = Fund.registerFund("F1", 1000L);
        assertEquals("F1", fund.getFundId());
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
        Fund fund = Fund.clearFundsGenesis("F2", 800L, "TX-002");
        assertEquals("F2", fund.getFundId());
        assertNull(fund.getPledgedAmount());
        assertEquals(800L, fund.getClearedAmount());
        assertEquals(800L, fund.getAvailableAmount());
    }

    @Test
    void testSagaHappyPath_RequestAndConfirm() {
        Fund fund = Fund.clearFundsGenesis("F1", 1000L, "TX");
        
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
        Fund fund = Fund.clearFundsGenesis("F1", 1000L, "TX");
        
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
        Fund fund = Fund.clearFundsGenesis("F1", 1000L, "TX");
        
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
        Fund fund = Fund.clearFundsGenesis("F1", 1000L, "TX");
        
        fund.requestAllocation("ALLOC-1", 100L);
        assertThrows(DuplicateAllocationException.class, () -> fund.requestAllocation("ALLOC-1", 100L));

        fund.refund("REF-1", 50L, "Refund 1");
        assertThrows(DuplicateRefundException.class, () -> fund.refund("REF-1", 50L, "Refund 1 again"));
    }

    @Test
    void testInsufficientFundsForAllocation() {
        Fund fund = Fund.clearFundsGenesis("F1", 100L, "TX");
        assertThrows(InsufficientAvailableFundsException.class, () -> fund.requestAllocation("ALLOC-1", 150L));
    }
    
    @Test
    void testInvalidTransitions() {
        Fund fund = Fund.clearFundsGenesis("F1", 1000L, "TX");
        assertThrows(InvalidFundTransitionException.class, () -> fund.confirmAllocation("NON_EXISTENT"));
        assertThrows(InvalidFundTransitionException.class, () -> fund.reverseAllocation("NON_EXISTENT", "Reason"));
    }
}
