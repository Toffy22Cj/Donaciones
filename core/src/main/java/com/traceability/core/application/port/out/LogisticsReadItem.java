package com.traceability.core.application.port.out;

import java.math.BigDecimal;

public record LogisticsReadItem(
    String assetId,
    String lifecycleStatus,
    String assetType,
    String unitOfMeasure,
    BigDecimal quantity,
    String currentLocation,
    String currentCustodian
) {
}
