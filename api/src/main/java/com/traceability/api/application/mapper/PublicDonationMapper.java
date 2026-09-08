package com.traceability.api.application.mapper;

import com.traceability.api.application.dto.PublicCustodianCategory;
import com.traceability.api.application.dto.PublicDonationStatus;
import com.traceability.api.application.dto.PublicDonationTrackingDTO;
import com.traceability.api.application.dto.PublicFinancialSnapshotDTO;
import com.traceability.api.application.dto.PublicLogisticsItemDTO;
import com.traceability.core.application.port.out.DonationReadModel;
import com.traceability.core.application.port.out.LogisticsReadItem;
import com.traceability.core.application.security.AssetRefService;
import com.traceability.core.application.service.LocationReferenceService;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PublicDonationMapper {

    private final AssetRefService assetRefService;
    private final LocationReferenceService locationReferenceService;

    public PublicDonationMapper(AssetRefService assetRefService, LocationReferenceService locationReferenceService) {
        this.assetRefService = assetRefService;
        this.locationReferenceService = locationReferenceService;
    }

    public PublicDonationTrackingDTO toPublicDTO(DonationReadModel model) {
        PublicDonationStatus publicStatus;
        if ("ACTIVE".equals(model.status())) {
            publicStatus = PublicDonationStatus.ACTIVA;
        } else if ("PAUSED".equals(model.status())) {
            publicStatus = PublicDonationStatus.EN_PROCESO;
        } else {
            throw new IllegalArgumentException("Unexpected status in DonationReadModel: " + model.status());
        }

        PublicFinancialSnapshotDTO financialSnapshot = new PublicFinancialSnapshotDTO(
                model.currency(),
                model.originalAmount(),
                model.clearedAmount(),
                model.pendingAllocationAmount(),
                model.confirmedAllocationAmount(),
                model.refundedAmount()
        );

        List<PublicLogisticsItemDTO> logistics = model.logistics() != null
                ? model.logistics().stream().map(this::mapLogisticsItem).toList()
                : List.of();

        return new PublicDonationTrackingDTO(
                financialSnapshot,
                model.campaignRef(),
                logistics,
                publicStatus
        );
    }

    private PublicLogisticsItemDTO mapLogisticsItem(LogisticsReadItem item) {
        String assetRef = assetRefService.computeRef(item.assetId());
        String locationZone = locationReferenceService.resolveZone(item.currentLocation()).orElse(null);
        PublicCustodianCategory custodianCategory = mapCustodian(item.lifecycleStatus());

        return new PublicLogisticsItemDTO(
                assetRef,
                item.lifecycleStatus(),
                item.assetType(),
                item.unitOfMeasure(),
                item.quantity(),
                locationZone,
                custodianCategory
        );
    }

    public PublicCustodianCategory mapCustodian(String lifecycleStatus) {
        if (lifecycleStatus == null) {
            throw new IllegalArgumentException("lifecycleStatus cannot be null");
        }

        return switch (lifecycleStatus) {
            case "REGISTERED" -> PublicCustodianCategory.UNCATEGORIZED;
            case "DISPATCHED" -> PublicCustodianCategory.LOGISTICS_PARTNER;
            case "RECEIVED" -> PublicCustodianCategory.REGIONAL_WAREHOUSE;
            case "DELIVERED" -> PublicCustodianCategory.LAST_MILE_CARRIER;
            case "DEPLETED" -> PublicCustodianCategory.UNCATEGORIZED;
            default -> throw new IllegalArgumentException("Unexpected lifecycleStatus: " + lifecycleStatus);
        };
    }
}
