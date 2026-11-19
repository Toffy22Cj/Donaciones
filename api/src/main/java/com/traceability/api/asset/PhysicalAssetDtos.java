package com.traceability.api.asset;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Cuerpos de las rutas de activos (plan B6-c §2.2). Cantidades como texto (T-34); nulos omitidos (Q-B60-2). */
public final class PhysicalAssetDtos {

    private PhysicalAssetDtos() {}

    /**
     * Camino A. Sin {@code organizationRef}: sale del JWT (DD-10). {@code municipalityCode}: el código DIVIPOLA de 5
     * dígitos, obligatorio (ADR-029 Enmienda 2); {@code currentLocation} sigue siendo texto libre y privado.
     * {@code evidenceRef}, opcional (ADR-051 Enmienda 2, E2-1): la huella {@code sha256:} de la foto del registro.
     */
    public record RegisterRequest(String fundId, String assetType, String quantity, String unitOfMeasure, String custodianRef,
                           String currentLocation, String allocationId, String sourceAllocationId,
                           String municipalityCode, String evidenceRef) {}

    /**
     * Camino B. Sin {@code organizationRef} (DD-10) ni {@code donorRef} (DD-9): si el cliente los envía, se ignoran.
     * {@code municipalityCode} obligatorio (ADR-029 Enmienda 2). {@code evidenceRef}, opcional (ADR-051 Enmienda 2, E2-1).
     */
    public record RegisterFromDonationRequest(String assetType, String quantity, String unitOfMeasure, String custodianRef,
                                       String currentLocation, String campaignRef, String municipalityCode,
                                       String evidenceRef) {}

    public record SplitRequest(String quantity) {}

    /** {@code evidenceRef}, opcional (ADR-051 Enmienda 2, E2-1). */
    public record DispatchRequest(String carrierRef, String evidenceRef) {}

    /** {@code municipalityCode} obligatorio (ADR-029 Enmienda 2). {@code evidenceRef}, opcional (ADR-051 Enmienda 2, E2-1). */
    public record ReceiveRequest(String facilityLocation, String receiverRef, String municipalityCode, String evidenceRef) {}

    /** Sin {@code deliveredAt}: lo pone el servidor (DD-15). {@code municipalityCode} obligatorio (ADR-029 Enmienda 2). */
    public record DeliverRequest(String finalCustodianRef, String beneficiaryRef, String locationRef, String evidenceRef,
                                 String municipalityCode) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RegisteredResponse(String assetRef, String status, String donationRef, String campaignRef) {}

    public record SplitAcceptedResponse(String parentAssetRef, String childAssetRef, String status) {}

    public record SplitStatusResponse(String status) {}

    public record TransitionResponse(String assetRef, String status) {}

    /**
     * {@code assetType}: ficha R-6, G-4b. Sin genealogía ({@code parentAssetRef}, {@code rootAssetRef}) ni donante.
     * {@code municipalityCode} y {@code zoneName}: la zona sellada (ADR-029 Enmienda 2, P6), ausentes si no la hay.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OperationalResponse(String assetRef, String lifecycleStatus, String currentCustodianRef,
                               String currentLocation, String quantity, String unitOfMeasure, String campaignRef,
                               String assetType, String municipalityCode, String zoneName) {}
}
