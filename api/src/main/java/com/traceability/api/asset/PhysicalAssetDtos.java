package com.traceability.api.asset;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Cuerpos de las rutas de activos (plan B6-c §2.2). Cantidades como texto (T-34); nulos omitidos (Q-B60-2). */
public final class PhysicalAssetDtos {

    private PhysicalAssetDtos() {}

    /** Camino A. Sin {@code organizationRef}: sale del JWT (DD-10). */
    public record RegisterRequest(String fundId, String assetType, String quantity, String unitOfMeasure, String custodianRef,
                           String currentLocation, String allocationId, String sourceAllocationId) {}

    /** Camino B. Sin {@code organizationRef} (DD-10) ni {@code donorRef} (DD-9): si el cliente los envía, se ignoran. */
    public record RegisterFromDonationRequest(String assetType, String quantity, String unitOfMeasure, String custodianRef,
                                       String currentLocation, String campaignRef) {}

    public record SplitRequest(String quantity) {}

    public record DispatchRequest(String carrierRef) {}

    public record ReceiveRequest(String facilityLocation, String receiverRef) {}

    /** Sin {@code deliveredAt}: lo pone el servidor (DD-15). */
    public record DeliverRequest(String finalCustodianRef, String beneficiaryRef, String locationRef, String evidenceRef) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RegisteredResponse(String assetRef, String status, String donationRef, String campaignRef) {}

    public record SplitAcceptedResponse(String parentAssetRef, String childAssetRef, String status) {}

    public record SplitStatusResponse(String status) {}

    public record TransitionResponse(String assetRef, String status) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OperationalResponse(String assetRef, String lifecycleStatus, String currentCustodianRef,
                               String currentLocation, String quantity, String unitOfMeasure, String campaignRef) {}
}
