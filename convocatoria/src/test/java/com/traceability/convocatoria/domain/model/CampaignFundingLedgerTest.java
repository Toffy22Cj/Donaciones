package com.traceability.convocatoria.domain.model;

import com.traceability.convocatoria.domain.exception.CloseOnTargetCloseNotSupportedException;
import org.junit.jupiter.api.Test;

import static com.traceability.convocatoria.domain.model.DomainFixtures.flexibleMonetary;
import static com.traceability.convocatoria.domain.model.DomainFixtures.inKindOnly;
import static com.traceability.convocatoria.domain.model.DomainFixtures.monetary;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ADR-037 §2.2; Enmienda §3.1 (N2), §3.3; implementation_plan.md §8. */
class CampaignFundingLedgerTest {

    @Test
    void opensWithZeroClearedAmountFromMonetaryConfiguration() {
        CampaignFundingLedger ledger = CampaignFundingLedger.open("camp", flexibleMonetary());
        assertEquals(0L, ledger.clearedAmount());
        assertEquals(1000L, ledger.targetAmount());
        assertEquals("COP", ledger.currency());
    }

    @Test
    void inKindOnlyHasNoLedger() {
        assertThrows(IllegalStateException.class, () -> CampaignFundingLedger.open("camp", inKindOnly()));
    }

    @Test
    void capacityLimitByPolicy() {
        assertFalse(CampaignFundingLedger.open("c", flexibleMonetary()).isCapacityLimited());
        assertTrue(CampaignFundingLedger.open("c", monetary(TargetPolicy.STRICT, null, PaymentMethod.GATEWAY))
                .isCapacityLimited());
        assertTrue(CampaignFundingLedger.open("c", monetary(TargetPolicy.CLOSE_ON_TARGET,
                OnTargetReached.REJECT_EXCESS, PaymentMethod.GATEWAY)).isCapacityLimited());
        assertFalse(CampaignFundingLedger.open("c", monetary(TargetPolicy.CLOSE_ON_TARGET,
                OnTargetReached.ACCEPT_EXCESS, PaymentMethod.GATEWAY)).isCapacityLimited());
    }

    @Test
    void closeOnTargetCloseIsOutOfThisCut() {
        CampaignFundingLedger ledger = CampaignFundingLedger.open("c", monetary(TargetPolicy.CLOSE_ON_TARGET,
                OnTargetReached.CLOSE, PaymentMethod.GATEWAY));
        assertThrows(CloseOnTargetCloseNotSupportedException.class, ledger::isCapacityLimited);
    }
}
