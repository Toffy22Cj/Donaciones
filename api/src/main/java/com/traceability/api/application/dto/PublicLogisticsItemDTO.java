package com.traceability.api.application.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;

public record PublicLogisticsItemDTO(
    String assetRef,
    String lifecycleStatus,
    String assetType,
    String unitOfMeasure,
    @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal quantity,
    String locationZone,
    PublicCustodianCategory custodianCategory,
    /** Ficha R-6, G-4: {@code assetRef} opaco del lote de la misma {@code logistics} del que salió; falta si no hay. */
    @JsonInclude(JsonInclude.Include.NON_NULL) String parentAssetRef
) {
}
