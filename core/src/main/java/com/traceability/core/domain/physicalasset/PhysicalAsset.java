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
import java.util.Objects;
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
    private String organizationRef;
    private String donorRef;
    private String donationRef;
    /** Convocatoria del activo (ADR-029 Enmienda 1). Inmutable; {@code null} = sin convocatoria. */
    private String campaignRef;

    // final delivery metadata for idempotency checking.
    // finalBeneficiaryRef is only replayed from ASSET_DELIVERED to compare redeliveries; it never
    // touches custodianRef (ADR-014).
    private String finalBeneficiaryRef;
    private String finalEvidenceRef;
    private Instant finalDeliveredAt;

    private final Map<String, AssetLifecycleStatus> splitsBeforeCompensation = new HashMap<>();
    private final Set<String> compensatedSplits = new HashSet<>();
    /** Lo que fijó cada {@code ASSET_SPLIT} para su hijo (D-SPLIT S3). */
    private final Map<String, SplitRecord> splits = new HashMap<>();

    // Protected constructor for rehydration via AggregateRoot
    protected PhysicalAsset() {
    }

    public static PhysicalAsset rehydrate(String streamId, Iterable<DomainEventPayload> payloads, long version) {
        PhysicalAsset asset = new PhysicalAsset();
        asset.streamId = streamId;
        asset.replay(payloads, version);
        return asset;
    }

    /** Camino A sin convocatoria (equivale a {@code campaignRef = null}). */
    public static PhysicalAsset register(
            String assetId, String assetType, BigDecimal quantity, String unitOfMeasure,
            String currentLocation, String custodianRef, String parentAssetRef,
            String rootAssetRef, String allocationId, String sourceAllocationId,
            String organizationRef, String donorRef) {
        return register(assetId, assetType, quantity, unitOfMeasure, currentLocation, custodianRef, parentAssetRef,
                rootAssetRef, allocationId, sourceAllocationId, organizationRef, donorRef, null);
    }

    /**
     * Camino A. Escribe {@code ASSET_REGISTERED} 3.0 (ADR-029 Enmienda 1, D1). {@code campaignRef} es el del
     * {@code Fund} (D2): lo resuelve el servicio de aplicación, nunca el llamador; puede ser {@code null}.
     */
    public static PhysicalAsset register(
            String assetId, String assetType, BigDecimal quantity, String unitOfMeasure,
            String currentLocation, String custodianRef, String parentAssetRef,
            String rootAssetRef, String allocationId, String sourceAllocationId,
            String organizationRef, String donorRef, String campaignRef) {

        if (organizationRef == null || organizationRef.isBlank()) {
            throw new IllegalArgumentException("OrganizationRef is required");
        }
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
        asset.raiseEvent(PhysicalAssetEventType.ASSET_REGISTERED, new AssetRegisteredV3Payload(
                assetId, assetType, quantity, unitOfMeasure, currentLocation, custodianRef,
                parentAssetRef, rootAssetRef, allocationId, sourceAllocationId, organizationRef, donorRef, null,
                campaignRef));
        return asset;
    }

    /** Camino B sin convocatoria (equivale a {@code campaignRef = null}). */
    public static PhysicalAsset create(
            String assetId, String assetType, BigDecimal quantity, String unitOfMeasure,
            String currentLocation, String custodianRef, String parentAssetRef,
            String rootAssetRef, String allocationId, String sourceAllocationId,
            String organizationRef, String donorRef, String donationRef) {
        return create(assetId, assetType, quantity, unitOfMeasure, currentLocation, custodianRef, parentAssetRef,
                rootAssetRef, allocationId, sourceAllocationId, organizationRef, donorRef, donationRef, null);
    }

    /**
     * Camino B. Escribe {@code ASSET_REGISTERED} 3.0 (ADR-029 Enmienda 1, D1). {@code campaignRef} lo recibe el comando
     * y lo valida el servicio de aplicación contra {@code convocatoria} antes de llegar aquí (D3); puede ser
     * {@code null}.
     */
    public static PhysicalAsset create(
            String assetId, String assetType, BigDecimal quantity, String unitOfMeasure,
            String currentLocation, String custodianRef, String parentAssetRef,
            String rootAssetRef, String allocationId, String sourceAllocationId,
            String organizationRef, String donorRef, String donationRef, String campaignRef) {

        if (organizationRef == null || organizationRef.isBlank()) {
            throw new IllegalArgumentException("OrganizationRef is required");
        }
        if (donorRef == null || donorRef.isBlank()) {
            throw new IllegalArgumentException("DonorRef is required");
        }
        if (donationRef == null || donationRef.isBlank()) {
            throw new IllegalArgumentException("DonationRef is required");
        }
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
        asset.raiseEvent(PhysicalAssetEventType.ASSET_REGISTERED, new AssetRegisteredV3Payload(
                assetId, assetType, quantity, unitOfMeasure, currentLocation, custodianRef,
                parentAssetRef, rootAssetRef, allocationId, sourceAllocationId, organizationRef, donorRef, donationRef,
                campaignRef));
        return asset;
    }

    /**
     * Génesis del hijo de una división (D-SPLIT S3; B1-bis). Escribe {@code ASSET_REGISTERED} 3.0. Origen de cada
     * atributo (P4):
     * <ul>
     *   <li>del {@code ASSET_SPLIT}: cantidad, unidad, ubicación y custodio <em>en el momento de la división</em> (son
     *       mutables en el padre), {@code rootAssetRef} y las referencias heredadas {@code organizationRef},
     *       {@code donorRef}, {@code donationRef} y {@code campaignRef} (ADR-029 §3; Enmienda 1, D4);</li>
     *   <li>del padre, solo atributos inmutables desde su registro: {@code assetType} y la asignación de origen
     *       ({@code allocationId} del padre o, si el padre ya es hijo, su {@code sourceAllocationId}).</li>
     * </ul>
     * No exige nada de {@code donorRef}/{@code donationRef}: el hijo hereda lo que tenga el padre, de cualquier camino.
     */
    public static PhysicalAsset registerSplitChild(PhysicalAsset parent, String childAssetId) {
        SplitRecord split = parent.findSplit(childAssetId).orElseThrow(() -> new IllegalArgumentException(
                "Asset " + parent.assetId + " has no split with child " + childAssetId));
        String sourceAllocationId = parent.allocationId != null ? parent.allocationId : parent.sourceAllocationId;

        PhysicalAsset child = new PhysicalAsset();
        child.raiseEvent(PhysicalAssetEventType.ASSET_REGISTERED, new AssetRegisteredV3Payload(
                childAssetId, parent.assetType, split.extractedQuantity(), split.unitOfMeasure(),
                split.childLocation(), split.childCustodianRef(), parent.assetId, split.rootAssetRef(),
                null, sourceAllocationId, split.organizationRef(), split.donorRef(), split.donationRef(),
                split.campaignRef()));
        return child;
    }

    /** El {@code ASSET_SPLIT} (v1, v2 o v3) que generó ese hijo. */
    public java.util.Optional<SplitRecord> findSplit(String childAssetId) {
        return java.util.Optional.ofNullable(splits.get(childAssetId));
    }

    private void checkOrganizationAssigned() {
        if (this.organizationRef == null) {
            throw new PhysicalAssetNotAssociatedToOrganizationException(
                    "PhysicalAsset " + assetId + " is not associated to any organization");
        }
    }

    public void dispatch(String carrierRef) {
        checkOrganizationAssigned();
        if (lifecycleStatus != AssetLifecycleStatus.REGISTERED && lifecycleStatus != AssetLifecycleStatus.RECEIVED) {
            throw new InvalidAssetTransitionException("Cannot dispatch asset in status " + lifecycleStatus);
        }
        if (currentLocation == null) {
            throw new InvalidAssetTransitionException("Cannot dispatch asset without current location");
        }

        raiseEvent(PhysicalAssetEventType.ASSET_DISPATCHED, new AssetDispatchedPayload(
                carrierRef, this.currentLocation));
    }

    public void receive(String facilityLocation, String receiverRef) {
        checkOrganizationAssigned();
        if (lifecycleStatus != AssetLifecycleStatus.DISPATCHED) {
            throw new InvalidAssetTransitionException("Cannot receive asset that is not dispatched");
        }
        if (facilityLocation == null) {
            throw new IllegalArgumentException("Facility location is required");
        }

        raiseEvent(PhysicalAssetEventType.ASSET_RECEIVED, new AssetReceivedPayload(
                facilityLocation, receiverRef));
    }

    public void transferCustody(String newCustodianRef) {
        checkOrganizationAssigned();
        if (lifecycleStatus == AssetLifecycleStatus.DELIVERED || lifecycleStatus == AssetLifecycleStatus.DEPLETED) {
            throw new AssetTerminalStateException("Cannot transfer custody of terminal asset");
        }
        if (this.custodianRef.equals(newCustodianRef)) {
            return;
        }

        raiseEvent(PhysicalAssetEventType.ASSET_CUSTODY_TRANSFERRED, new AssetCustodyTransferredPayload(
                this.custodianRef, newCustodianRef));
    }

    public void split(String childAssetId, BigDecimal extractedQuantity) {
        checkOrganizationAssigned();
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

        // ADR-029 Enmienda 1, D4: el hijo hereda también el campaignRef del padre (3.0).
        raiseEvent(PhysicalAssetEventType.ASSET_SPLIT, new AssetSplitV3Payload(
                childAssetId, extractedQuantity, this.unitOfMeasure, previousQ,
                previousQ.subtract(extractedQuantity), this.lifecycleStatus.name(),
                this.currentLocation, this.custodianRef, this.rootAssetRef,
                this.organizationRef, this.donorRef, this.donationRef, this.campaignRef));

        if (this.quantity.compareTo(BigDecimal.ZERO) == 0) {
            raiseEvent(PhysicalAssetEventType.ASSET_DEPLETED, new AssetDepletedPayload(previousQ));
        }
    }

    public void compensateSplit(String childAssetId, BigDecimal reintegratedQuantity) {
        checkOrganizationAssigned();
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
        BigDecimal extracted = splits.get(childAssetId).extractedQuantity();
        if (extracted != null && reintegratedQuantity.compareTo(extracted) != 0) {
            throw new InvalidCompensationQuantityException("Compensation of split " + childAssetId + " must reintegrate "
                    + extracted + ", not " + reintegratedQuantity);
        }
        raiseEvent(PhysicalAssetEventType.ASSET_SPLIT_COMPENSATED, new AssetSplitCompensatedPayload(
                childAssetId, reintegratedQuantity));
    }

    public void deliver(String finalCustodianRef, String beneficiaryRef, String locationRef, String evidenceRef,
            Instant deliveredAt) {
        checkOrganizationAssigned();
        if (lifecycleStatus == AssetLifecycleStatus.DELIVERED) {
            if (Objects.equals(this.custodianRef, finalCustodianRef)
                    && Objects.equals(this.finalBeneficiaryRef, beneficiaryRef)
                    && Objects.equals(this.currentLocation, locationRef)
                    && Objects.equals(this.finalEvidenceRef, evidenceRef)
                    && Objects.equals(this.finalDeliveredAt, deliveredAt)) {
                return;
            } else {
                throw new InvalidAssetTransitionException("Asset already delivered with different parameters");
            }
        }
        if (lifecycleStatus != AssetLifecycleStatus.DISPATCHED && lifecycleStatus != AssetLifecycleStatus.RECEIVED) {
            throw new InvalidAssetTransitionException("Cannot deliver asset in status " + lifecycleStatus);
        }

        raiseEvent(PhysicalAssetEventType.ASSET_DELIVERED, new AssetDeliveredPayload(
                finalCustodianRef, beneficiaryRef, locationRef, evidenceRef, deliveredAt));
    }

    @Override
    protected void apply(DomainEventPayload payload) {
        switch (payload) {
            case AssetRegisteredV3Payload p -> {
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
                this.organizationRef = p.organizationRef();
                this.donorRef = p.donorRef();
                this.donationRef = p.donationRef();
                this.campaignRef = p.campaignRef();
            }
            // 1.0 y 2.0 (histórico): sin convocatoria, nunca se infiere (ADR-029 Enmienda 1, D5/D6).
            case AssetRegisteredV2Payload p -> {
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
                this.organizationRef = p.organizationRef();
                this.donorRef = p.donorRef();
                this.donationRef = p.donationRef();
            }
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
            case AssetSplitV3Payload p -> {
                this.splits.put(p.childAssetId(), new SplitRecord(p.childAssetId(), scaled(p.extractedQuantity()),
                        p.unitOfMeasure(), p.childLocation(), p.childCustodianRef(), p.rootAssetRef(),
                        p.organizationRef(), p.donorRef(), p.donationRef(), p.campaignRef()));
                this.splitsBeforeCompensation.put(p.childAssetId(),
                        AssetLifecycleStatus.valueOf(p.statusBeforeSplit()));
                this.quantity = this.quantity.subtract(p.extractedQuantity().setScale(4, RoundingMode.HALF_UP));
            }
            case AssetSplitV2Payload p -> {
                this.splits.put(p.childAssetId(), new SplitRecord(p.childAssetId(), scaled(p.extractedQuantity()),
                        p.unitOfMeasure(), p.childLocation(), p.childCustodianRef(), p.rootAssetRef(),
                        p.organizationRef(), p.donorRef(), p.donationRef(), null));
                this.splitsBeforeCompensation.put(p.childAssetId(),
                        AssetLifecycleStatus.valueOf(p.statusBeforeSplit()));
                this.quantity = this.quantity.subtract(p.extractedQuantity().setScale(4, RoundingMode.HALF_UP));
            }
            case AssetSplitPayload p -> {
                // 1.0: sin referencias en el payload; se toman del padre, inmutables desde su registro
                this.splits.put(p.childAssetId(), new SplitRecord(p.childAssetId(), scaled(p.extractedQuantity()),
                        p.unitOfMeasure(), p.childLocation(), p.childCustodianRef(), p.rootAssetRef(),
                        this.organizationRef, this.donorRef, this.donationRef, null));
                this.splitsBeforeCompensation.put(p.childAssetId(),
                        AssetLifecycleStatus.valueOf(p.statusBeforeSplit()));
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
                this.finalBeneficiaryRef = p.beneficiaryRef();
                this.finalEvidenceRef = p.evidenceRef();
                this.finalDeliveredAt = p.deliveredAt();
            }
            default -> throw new IllegalArgumentException("Unknown payload type: " + payload.getClass());
        }
    }

    private static BigDecimal scaled(BigDecimal quantity) {
        return quantity == null ? null : quantity.setScale(4, RoundingMode.HALF_UP);
    }

    // Getters for testing
    public String getAssetId() {
        return assetId;
    }

    public AssetLifecycleStatus getLifecycleStatus() {
        return lifecycleStatus;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public String getUnitOfMeasure() {
        return unitOfMeasure;
    }

    public String getCurrentLocation() {
        return currentLocation;
    }

    public String getLastKnownLocation() {
        return lastKnownLocation;
    }

    public String getCustodianRef() {
        return custodianRef;
    }

    public String getOrganizationRef() {
        return organizationRef;
    }

    public String getDonorRef() {
        return donorRef;
    }

    public String getCampaignRef() {
        return campaignRef;
    }

    public String getDonationRef() {
        return donationRef;
    }

    public String getAssetType() {
        return assetType;
    }

    public String getAllocationId() {
        return allocationId;
    }

    public String getSourceAllocationId() {
        return sourceAllocationId;
    }

    public String getParentAssetRef() {
        return parentAssetRef;
    }

    public String getRootAssetRef() {
        return rootAssetRef;
    }
}
