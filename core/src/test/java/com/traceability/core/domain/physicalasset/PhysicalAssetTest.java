package com.traceability.core.domain.physicalasset;

import com.traceability.core.domain.event.DomainEvent;
import com.traceability.core.domain.event.DomainEventPayload;
import com.traceability.core.domain.physicalasset.exceptions.*;
import com.traceability.core.domain.physicalasset.payloads.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PhysicalAssetTest {

    @Test
    void testRegisterAsset_Success() {
        PhysicalAsset asset = PhysicalAsset.register(
            "A1", "VACCINE", new java.math.BigDecimal("100.0000"), "Vial", "LOC_A", "CUST_A", null, "A1", "ALLOC_1", null
        );

        assertEquals("A1", asset.getAssetId());
        assertEquals(new java.math.BigDecimal("100.0000"), asset.getQuantity());
        assertEquals(AssetLifecycleStatus.REGISTERED, asset.getLifecycleStatus());
        assertEquals("LOC_A", asset.getCurrentLocation());
        assertEquals("LOC_A", asset.getLastKnownLocation());
        assertEquals("CUST_A", asset.getCustodianRef());

        assertEquals(1, asset.getUncommittedEvents().size());
        assertTrue(asset.getUncommittedEvents().get(0).payload() instanceof AssetRegisteredPayload);
    }

    @Test
    void testDispatchAsset_SuccessAndMaintainsLastKnownLocation() {
        PhysicalAsset asset = PhysicalAsset.register("A1", "V", new java.math.BigDecimal("100.0000"), "U", "LOC_A", "CUST_A", null, "A1", null, null);
        asset.clearUncommittedEvents();

        asset.dispatch("CARRIER_1");

        assertEquals(AssetLifecycleStatus.DISPATCHED, asset.getLifecycleStatus());
        assertNull(asset.getCurrentLocation()); // Dispatched means in transit
        assertEquals("LOC_A", asset.getLastKnownLocation()); // ADR-014
        assertEquals("CARRIER_1", asset.getCustodianRef());
        
        assertEquals(1, asset.getUncommittedEvents().size());
        assertTrue(asset.getUncommittedEvents().get(0).payload() instanceof AssetDispatchedPayload);
    }

    @Test
    void testReceiveAsset_Success() {
        PhysicalAsset asset = PhysicalAsset.register("A1", "V", new java.math.BigDecimal("100.0000"), "U", "LOC_A", "CUST_A", null, "A1", null, null);
        asset.dispatch("CARRIER_1");
        asset.clearUncommittedEvents();

        asset.receive("LOC_B", "CUST_B");

        assertEquals(AssetLifecycleStatus.RECEIVED, asset.getLifecycleStatus());
        assertEquals("LOC_B", asset.getCurrentLocation());
        assertEquals("LOC_B", asset.getLastKnownLocation());
        assertEquals("CUST_B", asset.getCustodianRef());
    }

    @Test
    void testTransferCustody_Success() {
        PhysicalAsset asset = PhysicalAsset.register("A1", "V", new java.math.BigDecimal("100.0000"), "U", "LOC_A", "CUST_A", null, "A1", null, null);
        asset.clearUncommittedEvents();

        asset.transferCustody("CUST_B");

        assertEquals("CUST_B", asset.getCustodianRef());
    }
    
    @Test
    void testDeliverAsset_Success() {
        PhysicalAsset asset = PhysicalAsset.register("A1", "V", new java.math.BigDecimal("100.0000"), "U", "LOC_A", "CUST_A", null, "A1", null, null);
        asset.dispatch("CARRIER_1");
        asset.clearUncommittedEvents();

        asset.deliver("CLINIC_1", "BENEFICIARY_1", "LOC_FINAL", "EVIDENCE_1", Instant.now());

        assertEquals(AssetLifecycleStatus.DELIVERED, asset.getLifecycleStatus());
        assertEquals("LOC_FINAL", asset.getCurrentLocation());
        assertEquals("LOC_FINAL", asset.getLastKnownLocation());
        assertEquals("CLINIC_1", asset.getCustodianRef()); // beneficiary is NOT custodian
    }

    @Test
    void testDeliverAsset_RedundantDeliveryTreatedAsIdempotentSuccess() {
        PhysicalAsset asset = PhysicalAsset.register("A1", "V", new java.math.BigDecimal("100.0000"), "U", "LOC_A", "CUST_A", null, "A1", null, null);
        asset.dispatch("CARRIER_1");
        Instant time = Instant.now();
        asset.deliver("CLINIC_1", "BENEFICIARY_1", "LOC_FINAL", "EVIDENCE_1", time);
        
        // Exact same parameters throws RedundantDeliveryException
        assertThrows(RedundantDeliveryException.class, () -> {
            asset.deliver("CLINIC_1", "BENEFICIARY_1", "LOC_FINAL", "EVIDENCE_1", time);
        });
    }

    @Test
    void testDeliverAsset_ConflictDeliveryTreatedAsError() {
        PhysicalAsset asset = PhysicalAsset.register("A1", "V", new java.math.BigDecimal("100.0000"), "U", "LOC_A", "CUST_A", null, "A1", null, null);
        asset.dispatch("CARRIER_1");
        Instant time = Instant.now();
        asset.deliver("CLINIC_1", "BENEFICIARY_1", "LOC_FINAL", "EVIDENCE_1", time);
        
        // Different parameters throws InvalidAssetTransitionException
        assertThrows(InvalidAssetTransitionException.class, () -> {
            asset.deliver("CLINIC_1", "BENEFICIARY_1", "LOC_DIFFERENT", "EVIDENCE_1", time);
        });
    }

    // -- Exception Tests --

    @Test
    void testDispatchFromInvalidStatus_ThrowsException() {
        PhysicalAsset asset = PhysicalAsset.register("A1", "V", new java.math.BigDecimal("100.0000"), "U", "LOC_A", "CUST_A", null, "A1", null, null);
        asset.dispatch("C1");
        
        // Cannot dispatch again since it's already dispatched
        assertThrows(InvalidAssetTransitionException.class, () -> asset.dispatch("C2"));
    }

    @Test
    void testTransferCustodyRedundant_ThrowsException() {
        PhysicalAsset asset = PhysicalAsset.register("A1", "V", new java.math.BigDecimal("100.0000"), "U", "LOC_A", "CUST_A", null, "A1", null, null);
        assertThrows(RedundantCustodyTransferException.class, () -> asset.transferCustody("CUST_A"));
    }

    @Test
    void testSplitWithInsufficientQuantity_ThrowsException() {
        PhysicalAsset asset = PhysicalAsset.register("A1", "V", new java.math.BigDecimal("100.0000"), "U", "LOC_A", "CUST_A", null, "A1", null, null);
        assertThrows(InsufficientQuantityException.class, () -> asset.split("A2", new java.math.BigDecimal("150.0000")));
    }
    
    @Test
    void testSplitWithSelfId_ThrowsException() {
        PhysicalAsset asset = PhysicalAsset.register("A1", "V", new java.math.BigDecimal("100.0000"), "U", "LOC_A", "CUST_A", null, "A1", null, null);
        assertThrows(InvalidSplitTargetException.class, () -> asset.split("A1", new java.math.BigDecimal("50.0000")));
    }

    // -- Complex Replay Test (ADR-008 & ADR-009) --
    
    @Test
    void testReplay_SplitDepletionAndCompensation() {
        PhysicalAsset asset = new PhysicalAsset();
        
        List<DomainEventPayload> historicalPayloads = List.of(
            new AssetRegisteredPayload("A1", "V", new java.math.BigDecimal("100.0000"), "U", "LOC_A", "CUST_A", null, "A1", null, null),
            new AssetDispatchedPayload("CARRIER_1", "LOC_A"),
            new AssetReceivedPayload("LOC_B", "CUST_B"),
            // Split 1 (extract 40)
            new AssetSplitPayload("A2", new java.math.BigDecimal("40.0000"), "U", new java.math.BigDecimal("100.0000"), new java.math.BigDecimal("60.0000"), "RECEIVED", "LOC_B", "CUST_B", "A1"),
            // Split 2 (extract 60) -> leads to DEPLETED
            new AssetSplitPayload("A3", new java.math.BigDecimal("60.0000"), "U", new java.math.BigDecimal("60.0000"), new java.math.BigDecimal("0.0000"), "RECEIVED", "LOC_B", "CUST_B", "A1"),
            new AssetDepletedPayload(new java.math.BigDecimal("60.0000")),
            // Compensate Split 2 (reintegrate 60)
            new AssetSplitCompensatedPayload("A3", new java.math.BigDecimal("60.0000"))
        );

        asset.replay(historicalPayloads, 7);

        assertEquals(new java.math.BigDecimal("60.0000"), asset.getQuantity());
        // Lifecycle status resurrects to RECEIVED (what it was before Split 2 depleted it)
        assertEquals(AssetLifecycleStatus.RECEIVED, asset.getLifecycleStatus());
        assertEquals(7, asset.getVersion());
        
        // Cannot compensate again
        assertThrows(DuplicateCompensationException.class, () -> asset.compensateSplit("A3", new java.math.BigDecimal("60.0000")));
    }
}
