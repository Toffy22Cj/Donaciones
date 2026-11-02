package com.traceability.core.application.port.out;

import java.math.BigDecimal;

public record LogisticsReadItem(
    String assetId,
    String lifecycleStatus,
    String assetType,
    String unitOfMeasure,
    BigDecimal quantity,
    String currentLocation,
    String currentCustodian,
    /** Ficha R-6, G-4: {@code assetId} del lote del que salió (interno; nulo si no viene de una división). */
    String parentAssetId,
    /** ADR-029 Enmienda 2, D5: la zona sellada de la ubicación actual; {@code null} si no la hay (respaldo con {@code currentLocation}). */
    String currentZoneName
) {
    /** Un lote sin zona sellada. */
    public LogisticsReadItem(String assetId, String lifecycleStatus, String assetType, String unitOfMeasure,
                             BigDecimal quantity, String currentLocation, String currentCustodian, String parentAssetId) {
        this(assetId, lifecycleStatus, assetType, unitOfMeasure, quantity, currentLocation, currentCustodian, parentAssetId,
                null);
    }
}
