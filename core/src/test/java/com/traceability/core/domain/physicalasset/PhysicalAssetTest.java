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
            "A1", "VACCINE", new java.math.BigDecimal("100.0000"), "Vial", "LOC_A", "CUST_A", null, "A1", "ALLOC_1", null, "ORG_1", null
        );

        assertEquals("A1", asset.getAssetId());
        assertEquals(new java.math.BigDecimal("100.0000"), asset.getQuantity());
        assertEquals(AssetLifecycleStatus.REGISTERED, asset.getLifecycleStatus());
        assertEquals("LOC_A", asset.getCurrentLocation());
        assertEquals("LOC_A", asset.getLastKnownLocation());
        assertEquals("CUST_A", asset.getCustodianRef());
        assertEquals("ORG_1", asset.getOrganizationRef());
        assertNull(asset.getDonorRef());

        assertEquals(1, asset.getUncommittedEvents().size());
        assertTrue(asset.getUncommittedEvents().get(0).payload() instanceof AssetRegisteredV2Payload);
    }

    @Test
    void testRegisterAsset_WithoutOrganization_ThrowsException() {
        assertThrows(IllegalArgumentException.class, () ->
            PhysicalAsset.register("A1", "VACCINE", new java.math.BigDecimal("100.0000"), "Vial", "LOC_A", "CUST_A", null, "A1", "ALLOC_1", null, null, null)
        );
        assertThrows(IllegalArgumentException.class, () ->
            PhysicalAsset.register("A1", "VACCINE", new java.math.BigDecimal("100.0000"), "Vial", "LOC_A", "CUST_A", null, "A1", "ALLOC_1", null, "  ", null)
        );
    }

    @Test
    void testDispatchAsset_SuccessAndMaintainsLastKnownLocation() {
        PhysicalAsset asset = PhysicalAsset.register("A1", "V", new java.math.BigDecimal("100.0000"), "U", "LOC_A", "CUST_A", null, "A1", null, null, "ORG_1", null);
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
        PhysicalAsset asset = PhysicalAsset.register("A1", "V", new java.math.BigDecimal("100.0000"), "U", "LOC_A", "CUST_A", null, "A1", null, null, "ORG_1", null);
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
        PhysicalAsset asset = PhysicalAsset.register("A1", "V", new java.math.BigDecimal("100.0000"), "U", "LOC_A", "CUST_A", null, "A1", null, null, "ORG_1", null);
        asset.clearUncommittedEvents();

        asset.transferCustody("CUST_B");

        assertEquals("CUST_B", asset.getCustodianRef());
    }
    
    @Test
    void testDeliverAsset_Success() {
        PhysicalAsset asset = PhysicalAsset.register("A1", "V", new java.math.BigDecimal("100.0000"), "U", "LOC_A", "CUST_A", null, "A1", null, null, "ORG_1", null);
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
        PhysicalAsset asset = PhysicalAsset.register("A1", "V", new java.math.BigDecimal("100.0000"), "U", "LOC_A", "CUST_A", null, "A1", null, null, "ORG_1", null);
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
        PhysicalAsset asset = PhysicalAsset.register("A1", "V", new java.math.BigDecimal("100.0000"), "U", "LOC_A", "CUST_A", null, "A1", null, null, "ORG_1", null);
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
        PhysicalAsset asset = PhysicalAsset.register("A1", "V", new java.math.BigDecimal("100.0000"), "U", "LOC_A", "CUST_A", null, "A1", null, null, "ORG_1", null);
        asset.dispatch("C1");
        
        // Cannot dispatch again since it's already dispatched
        assertThrows(InvalidAssetTransitionException.class, () -> asset.dispatch("C2"));
    }

    @Test
    void testTransferCustodyRedundant_ThrowsException() {
        PhysicalAsset asset = PhysicalAsset.register("A1", "V", new java.math.BigDecimal("100.0000"), "U", "LOC_A", "CUST_A", null, "A1", null, null, "ORG_1", null);
        assertThrows(RedundantCustodyTransferException.class, () -> asset.transferCustody("CUST_A"));
    }

    @Test
    void testSplitWithInsufficientQuantity_ThrowsException() {
        PhysicalAsset asset = PhysicalAsset.register("A1", "V", new java.math.BigDecimal("100.0000"), "U", "LOC_A", "CUST_A", null, "A1", null, null, "ORG_1", null);
        assertThrows(InsufficientQuantityException.class, () -> asset.split("A2", new java.math.BigDecimal("150.0000")));
    }
    
    @Test
    void testSplitWithSelfId_ThrowsException() {
        PhysicalAsset asset = PhysicalAsset.register("A1", "V", new java.math.BigDecimal("100.0000"), "U", "LOC_A", "CUST_A", null, "A1", null, null, "ORG_1", null);
        assertThrows(InvalidSplitTargetException.class, () -> asset.split("A1", new java.math.BigDecimal("50.0000")));
    }

    // -- Entregable 5 Test: Rejection on v1 assets without organization --

    @Test
    void testWriteCommandsOnAssetWithoutOrganization_ThrowsException() {
        PhysicalAsset asset = PhysicalAsset.rehydrate("A1", List.of(
            new AssetRegisteredPayload("A1", "V", new java.math.BigDecimal("100.0000"), "U", "LOC_A", "CUST_A", null, "A1", null, null)
        ), 1);

        assertNull(asset.getOrganizationRef());
        assertThrows(PhysicalAssetNotAssociatedToOrganizationException.class, () -> asset.dispatch("CARRIER_1"));
        assertThrows(PhysicalAssetNotAssociatedToOrganizationException.class, () -> asset.receive("LOC_B", "CUST_B"));
        assertThrows(PhysicalAssetNotAssociatedToOrganizationException.class, () -> asset.transferCustody("CUST_B"));
        assertThrows(PhysicalAssetNotAssociatedToOrganizationException.class, () -> asset.split("A2", new java.math.BigDecimal("50.0000")));
        assertThrows(PhysicalAssetNotAssociatedToOrganizationException.class, () -> asset.compensateSplit("A2", new java.math.BigDecimal("50.0000")));
        assertThrows(PhysicalAssetNotAssociatedToOrganizationException.class, () -> asset.deliver("CLINIC_1", "BENEFICIARY_1", "LOC_FINAL", "EVIDENCE_1", Instant.now()));
    }

    // -- Complex Replay Test (ADR-008 & ADR-009) --
    
    @Test
    void testReplay_SplitDepletionAndCompensation() {
        PhysicalAsset asset = new PhysicalAsset();
        
        List<DomainEventPayload> historicalPayloads = List.of(
            new AssetRegisteredV2Payload("A1", "V", new java.math.BigDecimal("100.0000"), "U", "LOC_A", "CUST_A", null, "A1", null, null, "ORG_1", null, null),
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
        assertEquals("ORG_1", asset.getOrganizationRef());
        // Lifecycle status resurrects to RECEIVED (what it was before Split 2 depleted it)
        assertEquals(AssetLifecycleStatus.RECEIVED, asset.getLifecycleStatus());
        assertEquals(7, asset.getVersion());
        
        // Cannot compensate again
        assertThrows(DuplicateCompensationException.class, () -> asset.compensateSplit("A3", new java.math.BigDecimal("60.0000")));
    }

    // -- Camino B (In-Kind Donation Genesis) Tests --

    @Test
    void testCreateAssetFromDonation_Success() {
        PhysicalAsset asset = PhysicalAsset.create(
            "A1", "MEDICINE", new java.math.BigDecimal("50.0000"), "Box", "WAREHOUSE_1", "CUST_1", null, "A1", null, null, "ORG_1", "DONOR_1", "DONATION_100"
        );

        assertEquals("A1", asset.getAssetId());
        assertEquals(new java.math.BigDecimal("50.0000"), asset.getQuantity());
        assertEquals(AssetLifecycleStatus.REGISTERED, asset.getLifecycleStatus());
        assertEquals("ORG_1", asset.getOrganizationRef());
        assertEquals("DONOR_1", asset.getDonorRef());
        assertEquals("DONATION_100", asset.getDonationRef());

        assertEquals(1, asset.getUncommittedEvents().size());
        assertTrue(asset.getUncommittedEvents().get(0).payload() instanceof AssetRegisteredV2Payload);
        AssetRegisteredV2Payload payload = (AssetRegisteredV2Payload) asset.getUncommittedEvents().get(0).payload();
        assertEquals("MEDICINE", payload.assetType());
        assertEquals("ORG_1", payload.organizationRef());
        assertEquals("DONOR_1", payload.donorRef());
        assertEquals("DONATION_100", payload.donationRef());
    }

    @Test
    void testCreateAssetFromDonation_MissingRequiredFields_ThrowsException() {
        // Missing organizationRef
        assertThrows(IllegalArgumentException.class, () ->
            PhysicalAsset.create("A1", "MEDICINE", new java.math.BigDecimal("50.0000"), "Box", "W1", "C1", null, "A1", null, null, null, "DONOR_1", "DONATION_100")
        );
        assertThrows(IllegalArgumentException.class, () ->
            PhysicalAsset.create("A1", "MEDICINE", new java.math.BigDecimal("50.0000"), "Box", "W1", "C1", null, "A1", null, null, "  ", "DONOR_1", "DONATION_100")
        );

        // Missing donorRef
        assertThrows(IllegalArgumentException.class, () ->
            PhysicalAsset.create("A1", "MEDICINE", new java.math.BigDecimal("50.0000"), "Box", "W1", "C1", null, "A1", null, null, "ORG_1", null, "DONATION_100")
        );
        assertThrows(IllegalArgumentException.class, () ->
            PhysicalAsset.create("A1", "MEDICINE", new java.math.BigDecimal("50.0000"), "Box", "W1", "C1", null, "A1", null, null, "ORG_1", "  ", "DONATION_100")
        );

        // Missing donationRef
        assertThrows(IllegalArgumentException.class, () ->
            PhysicalAsset.create("A1", "MEDICINE", new java.math.BigDecimal("50.0000"), "Box", "W1", "C1", null, "A1", null, null, "ORG_1", "DONOR_1", null)
        );
        assertThrows(IllegalArgumentException.class, () ->
            PhysicalAsset.create("A1", "MEDICINE", new java.math.BigDecimal("50.0000"), "Box", "W1", "C1", null, "A1", null, null, "ORG_1", "DONOR_1", "  ")
        );
    }

    @Test
    void testSharedSchema_CaminoAAndCaminoBShareSameSchemaVersion() {
        PhysicalAsset assetCaminoA = PhysicalAsset.register(
            "A1", "VACCINE", new java.math.BigDecimal("100.0000"), "Vial", "LOC_A", "CUST_A", null, "A1", "ALLOC_1", null, "ORG_1", "DONOR_1"
        );
        PhysicalAsset assetCaminoB = PhysicalAsset.create(
            "A2", "MEDICINE", new java.math.BigDecimal("50.0000"), "Box", "LOC_B", "CUST_B", null, "A2", null, null, "ORG_1", "DONOR_1", "DONATION_100"
        );

        DomainEventPayload payloadA = assetCaminoA.getUncommittedEvents().get(0).payload();
        DomainEventPayload payloadB = assetCaminoB.getUncommittedEvents().get(0).payload();

        // Same record class schema
        assertEquals(AssetRegisteredV2Payload.class, payloadA.getClass());
        assertEquals(AssetRegisteredV2Payload.class, payloadB.getClass());

        AssetRegisteredV2Payload pA = (AssetRegisteredV2Payload) payloadA;
        AssetRegisteredV2Payload pB = (AssetRegisteredV2Payload) payloadB;

        // Structural equality of components count/types, differing only in value for donationRef
        assertNull(pA.donationRef());
        assertEquals("DONATION_100", pB.donationRef());
    }
}
