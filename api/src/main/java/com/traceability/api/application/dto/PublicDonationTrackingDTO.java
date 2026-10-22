package com.traceability.api.application.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * {@code campaignTitle} y {@code organizationName}: ficha R-6, G-1; faltan si no hay convocatoria o no se encuentra, y
 * {@code organizationName} también si la organización no tiene nombre. Sin {@code fundId} ni {@code donorRef} (DD-76).
 */
public record PublicDonationTrackingDTO(
    PublicFinancialSnapshotDTO financialSnapshot,
    @JsonInclude(JsonInclude.Include.NON_NULL) String campaignRef,
    List<PublicLogisticsItemDTO> logistics,
    PublicDonationStatus status,
    @JsonInclude(JsonInclude.Include.NON_NULL) String campaignTitle,
    @JsonInclude(JsonInclude.Include.NON_NULL) String organizationName
) {
}
