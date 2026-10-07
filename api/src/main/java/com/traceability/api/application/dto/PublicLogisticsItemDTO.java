package com.traceability.api.application.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;

public record PublicLogisticsItemDTO(
    String assetRef,
    String lifecycleStatus,
    String assetType,
    String unitOfMeasure,
    @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal quantity,
    String locationZone,
    PublicCustodianCategory custodianCategory
) {
}
