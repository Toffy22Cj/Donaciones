package com.traceability.core.domain.physicalasset;

import java.math.BigDecimal;

/**
 * Lo que un {@code ASSET_SPLIT} (v1, v2 o v3) fijó para el hijo (D-SPLIT S3, P4): cantidad, unidad, ubicación y
 * custodio <em>en el momento de la división</em>, y las referencias heredadas. En la v1, {@code organizationRef},
 * {@code donorRef} y {@code donationRef} se toman del padre (inmutables) y {@code campaignRef} es {@code null}.
 * {@code zone} es la zona sellada del padre que hereda el hijo (enmienda 2 de ADR-029, D4): solo la trae la v4, y es
 * {@code null} en las anteriores o si el padre no tenía zona (E-1).
 */
public record SplitRecord(
        String childAssetId,
        BigDecimal extractedQuantity,
        String unitOfMeasure,
        String childLocation,
        String childCustodianRef,
        String rootAssetRef,
        String organizationRef,
        String donorRef,
        String donationRef,
        String campaignRef,
        SealedZone zone
) {
    /** Las divisiones 1.0 a 3.0, sin zona. */
    public SplitRecord(String childAssetId, BigDecimal extractedQuantity, String unitOfMeasure, String childLocation,
                       String childCustodianRef, String rootAssetRef, String organizationRef, String donorRef,
                       String donationRef, String campaignRef) {
        this(childAssetId, extractedQuantity, unitOfMeasure, childLocation, childCustodianRef, rootAssetRef,
                organizationRef, donorRef, donationRef, campaignRef, null);
    }
}
