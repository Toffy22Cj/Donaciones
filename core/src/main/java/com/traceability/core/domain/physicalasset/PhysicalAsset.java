package com.traceability.core.domain.physicalasset;

import com.traceability.core.domain.event.DomainEventPayload;
import com.traceability.core.domain.physicalasset.exceptions.*;
import com.traceability.core.domain.physicalasset.payloads.*;
import com.traceability.core.domain.shared.AggregateRoot;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Aggregate Root for Physical Asset.
 * Ref: ADR-001, ADR-002, ADR-003, ADR-005, ADR-008, ADR-009, ADR-014
 */
public class PhysicalAsset extends AggregateRoot {
    private String assetId;
    private String assetType;
    private BigDecimal quantity;
    private String unitOfMeasure;
    private AssetLifecycleStatus lifecycleStatus;
    private String currentLocation;
    private String lastKnownLocation;
    private String custodianRef;
    private String parentAssetRef;
    private String rootAssetRef;
    private String allocationId;
    private String sourceAllocationId;
    
    // final delivery metadata for idempotency checking
    private String finalEvidenceRef;
    private Instant finalDeliveredAt;

    private final Map<String, AssetLifecycleStatus> splitsBeforeCompensation = new HashMap<>();
    private final Set<String> compensatedSplits = new HashSet<>();

    // Protected constructor for rehydration via AggregateRoot
    protected PhysicalAsset() {}

    public static PhysicalAsset rehydrate(String streamId, Iterable<DomainEventPayload> payloads, long version) {
        PhysicalAsset asset = new PhysicalAsset();
        asset.streamId = streamId;
        asset.replay(payloads, version);
        return asset;
    }

    public static PhysicalAsset register(
            String assetId, String assetType, BigDecimal quantity, String unitOfMeasure,
            String currentLocation, String custodianRef, String parentAssetRef,
            String rootAssetRef, String allocationId, String sourceAllocationId) {
        
        if (quantity == null) {
            throw new IllegalArgumentException("Quantity is required");
        }
        quantity = quantity.setScale(4, RoundingMode.HALF_UP);
        if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Quantity must be greater than 0");
        }
        if (unitOfMeasure == null || unitOfMeasure.isBlank()) {
            throw new IllegalArgumentException("Unit of measure is required");
        }
        if (currentLocation == null) {
            throw new IllegalArgumentException("Initial current location is required");
        }
        
        PhysicalAsset asset = new PhysicalAsset();
        asset.raiseEvent(PhysicalAssetEventType.ASSET_REGISTERED, new AssetRegisteredPayload(
            assetId, assetType, quantity, unitOfMeasure, currentLocation, custodianRef,
            parentAssetRef, rootAssetRef, allocationId, sourceAllocationId
        ));
        return asset;
    }

    public void dispatch(String carrierRef) {
        if (lifecycleStatus != AssetLifecycleStatus.REGISTERED && lifecycleStatus != AssetLifecycleStatus.RECEIVED) {
            throw new InvalidAssetTransitionException("Cannot dispatch asset in status " + lifecycleStatus);
        }
        if (currentLocation == null) {
            throw new InvalidAssetTransitionException("Cannot dispatch asset without current location");
        }
        
        raiseEvent(PhysicalAssetEventType.ASSET_DISPATCHED, new AssetDispatchedPayload(
            carrierRef, this.currentLocation
        ));
    }

    public void receive(String facilityLocation, String receiverRef) {
        if (lifecycleStatus != AssetLifecycleStatus.DISPATCHED) {
            throw new InvalidAssetTransitionException("Cannot receive asset that is not dispatched");
        }
        if (facilityLocation == null) {
            throw new IllegalArgumentException("Facility location is required");
        }
        
        raiseEvent(PhysicalAssetEventType.ASSET_RECEIVED, new AssetReceivedPayload(
            facilityLocation, receiverRef
        ));
    }

    public void transferCustody(String newCustodianRef) {
        if (lifecycleStatus == AssetLifecycleStatus.DELIVERED || lifecycleStatus == AssetLifecycleStatus.DEPLETED) {
            throw new AssetTerminalStateException("Cannot transfer custody of terminal asset");
        }
        if (this.custodianRef.equals(newCustodianRef)) {
            throw new RedundantCustodyTransferException("New custodian is same as current custodian");
        }
        
        raiseEvent(PhysicalAssetEventType.ASSET_CUSTODY_TRANSFERRED, new AssetCustodyTransferredPayload(
            this.custodianRef, newCustodianRef
        ));
    }

    public void split(String childAssetId, BigDecimal extractedQuantity) {
        if (lifecycleStatus != AssetLifecycleStatus.REGISTERED && lifecycleStatus != AssetLifecycleStatus.RECEIVED) {
            throw new InvalidAssetTransitionException("Cannot split asset in status " + lifecycleStatus);
        }
        if (extractedQuantity == null) {
            throw new InsufficientQuantityException("Extracted quantity is required");
        }
        extractedQuantity = extractedQuantity.setScale(4, RoundingMode.HALF_UP);
        if (extractedQuantity.compareTo(BigDecimal.ZERO) <= 0 || extractedQuantity.compareTo(this.quantity) > 0) {
            throw new InsufficientQuantityException("Invalid extracted quantity: " + extractedQuantity);
        }
        if (this.assetId.equals(childAssetId)) {
            throw new InvalidSplitTargetException("Child asset ID cannot be same as parent asset ID");
        }
        
        BigDecimal previousQ = this.quantity;
        
        raiseEvent(PhysicalAssetEventType.ASSET_SPLIT, new AssetSplitPayload(
            childAssetId, extractedQuantity, this.unitOfMeasure, previousQ,
            previousQ.subtract(extractedQuantity), this.lifecycleStatus.name(),
            this.currentLocation, this.custodianRef, this.rootAssetRef
        ));
        
        if (this.quantity.compareTo(BigDecimal.ZERO) == 0) {
            raiseEvent(PhysicalAssetEventType.ASSET_DEPLETED, new AssetDepletedPayload(previousQ));
        }
    }

    public void compensateSplit(String childAssetId, BigDecimal reintegratedQuantity) {
        if (lifecycleStatus == AssetLifecycleStatus.DELIVERED) {
            throw new AssetTerminalStateException("Cannot compensate split for DELIVERED asset");
        }
        if (!splitsBeforeCompensation.containsKey(childAssetId)) {
            throw new IllegalArgumentException("Split with childAssetId " + childAssetId + " not found in history");
        }
        if (compensatedSplits.contains(childAssetId)) {
            throw new DuplicateCompensationException("Split " + childAssetId + " was already compensated");
        }
        
        reintegratedQuantity = reintegratedQuantity.setScale(4, RoundingMode.HALF_UP);
        raiseEvent(PhysicalAssetEventType.ASSET_SPLIT_COMPENSATED, new AssetSplitCompensatedPayload(
            childAssetId, reintegratedQuantity
        ));
    }

    public void deliver(String finalCustodianRef, String beneficiaryRef, String locationRef, String evidenceRef, Instant deliveredAt) {
        if (lifecycleStatus == AssetLifecycleStatus.DELIVERED) {
            if (this.custodianRef.equals(finalCustodianRef) && this.currentLocation.equals(locationRef)
                && this.finalEvidenceRef.equals(evidenceRef) && this.finalDeliveredAt.equals(deliveredAt)) {
                throw new RedundantDeliveryException("Asset is already delivered with these exact parameters");
            } else {
                throw new InvalidAssetTransitionException("Asset already delivered with different parameters");
            }
        }
        if (lifecycleStatus != AssetLifecycleStatus.DISPATCHED && lifecycleStatus != AssetLifecycleStatus.RECEIVED) {
            throw new InvalidAssetTransitionException("Cannot deliver asset in status " + lifecycleStatus);
        }
        
        raiseEvent(PhysicalAssetEventType.ASSET_DELIVERED, new AssetDeliveredPayload(
            finalCustodianRef, beneficiaryRef, locationRef, evidenceRef, deliveredAt
        ));
    }

    @Override
    protected void apply(DomainEventPayload payload) {
        switch (payload) {
            case AssetRegisteredPayload p -> {
                this.assetId = p.assetId();
                this.assetType = p.assetType();
                this.quantity = p.quantity().setScale(4, RoundingMode.HALF_UP);
                this.unitOfMeasure = p.unitOfMeasure();
                this.lifecycleStatus = AssetLifecycleStatus.REGISTERED;
                this.currentLocation = p.currentLocation();
                this.lastKnownLocation = p.currentLocation();
                this.custodianRef = p.custodianRef();
                this.parentAssetRef = p.parentAssetRef();
                this.rootAssetRef = p.rootAssetRef();
                this.allocationId = p.allocationId();
                this.sourceAllocationId = p.sourceAllocationId();
            }
            case AssetDispatchedPayload p -> {
                this.lifecycleStatus = AssetLifecycleStatus.DISPATCHED;
                this.lastKnownLocation = p.previousLocation();
                this.currentLocation = null;
                this.custodianRef = p.carrierRef();
            }
            case AssetReceivedPayload p -> {
                this.lifecycleStatus = AssetLifecycleStatus.RECEIVED;
                this.currentLocation = p.facilityLocation();
                this.lastKnownLocation = p.facilityLocation();
                this.custodianRef = p.receiverRef();
            }
            case AssetCustodyTransferredPayload p -> {
                this.custodianRef = p.newCustodianRef();
            }
            case AssetSplitPayload p -> {
                this.splitsBeforeCompensation.put(p.childAssetId(), AssetLifecycleStatus.valueOf(p.statusBeforeSplit()));
                this.quantity = this.quantity.subtract(p.extractedQuantity().setScale(4, RoundingMode.HALF_UP));
            }
            case AssetDepletedPayload p -> {
                this.lifecycleStatus = AssetLifecycleStatus.DEPLETED;
            }
            case AssetSplitCompensatedPayload p -> {
                this.compensatedSplits.add(p.childAssetId());
                this.quantity = this.quantity.add(p.reintegratedQuantity().setScale(4, RoundingMode.HALF_UP));
                if (this.lifecycleStatus == AssetLifecycleStatus.DEPLETED) {
                    this.lifecycleStatus = this.splitsBeforeCompensation.get(p.childAssetId());
                }
            }
            case AssetDeliveredPayload p -> {
                this.lifecycleStatus = AssetLifecycleStatus.DELIVERED;
                this.currentLocation = p.locationRef();
                this.lastKnownLocation = p.locationRef();
                this.custodianRef = p.finalCustodianRef();
                this.finalEvidenceRef = p.evidenceRef();
                this.finalDeliveredAt = p.deliveredAt();
            }
            default -> throw new IllegalArgumentException("Unknown payload type: " + payload.getClass());
        }
    }
    
    // Getters for testing
    public String getAssetId() { return assetId; }
    public AssetLifecycleStatus getLifecycleStatus() { return lifecycleStatus; }
    public BigDecimal getQuantity() { return quantity; }
    public String getCurrentLocation() { return currentLocation; }
    public String getLastKnownLocation() { return lastKnownLocation; }
    public String getCustodianRef() { return custodianRef; }
}
