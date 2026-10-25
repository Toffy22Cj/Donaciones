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
    String parentAssetId
) {
}
