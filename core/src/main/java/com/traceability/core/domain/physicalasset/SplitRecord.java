package com.traceability.core.domain.physicalasset;

import java.math.BigDecimal;

/**
 * Lo que un {@code ASSET_SPLIT} (v1, v2 o v3) fijó para el hijo (D-SPLIT S3, P4): cantidad, unidad, ubicación y
 * custodio <em>en el momento de la división</em>, y las referencias heredadas. En la v1, {@code organizationRef},
 * {@code donorRef} y {@code donationRef} se toman del padre (inmutables) y {@code campaignRef} es {@code null}.
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
        String campaignRef
) {}
