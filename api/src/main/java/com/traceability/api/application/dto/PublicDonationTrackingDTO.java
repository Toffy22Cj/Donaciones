package com.traceability.api.application.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

public record PublicDonationTrackingDTO(
    PublicFinancialSnapshotDTO financialSnapshot,
    @JsonInclude(JsonInclude.Include.NON_NULL) String campaignRef,
    List<PublicLogisticsItemDTO> logistics,
    PublicDonationStatus status
) {
}
