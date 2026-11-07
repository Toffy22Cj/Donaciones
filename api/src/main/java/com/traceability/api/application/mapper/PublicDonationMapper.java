package com.traceability.api.application.mapper;

import com.traceability.api.application.dto.PublicCustodianCategory;
import com.traceability.api.application.dto.PublicDonationStatus;
import com.traceability.api.application.dto.PublicDonationTrackingDTO;
import com.traceability.api.application.dto.PublicFinancialSnapshotDTO;
import com.traceability.api.application.dto.PublicLogisticsItemDTO;
import com.traceability.contracts.campaign.CampaignPublicSummaryPort;
import com.traceability.contracts.campaign.CampaignPublicSummaryPort.CampaignPublicSummary;
import com.traceability.core.application.port.out.DonationReadModel;
import com.traceability.core.application.port.out.FundClearingDatesReadPort;
import com.traceability.core.application.port.out.FundClearingDatesReadPort.ClearingDates;
import com.traceability.core.application.port.out.LogisticsReadItem;
import com.traceability.core.application.security.AssetRefService;
import com.traceability.core.application.service.LocationReferenceService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class PublicDonationMapper {

    private final AssetRefService assetRefService;
    private final LocationReferenceService locationReferenceService;
    private final CampaignPublicSummaryPort campaignSummaries;
    private final FundClearingDatesReadPort clearingDates;

    public PublicDonationMapper(AssetRefService assetRefService, LocationReferenceService locationReferenceService,
                                CampaignPublicSummaryPort campaignSummaries, FundClearingDatesReadPort clearingDates) {
        this.assetRefService = assetRefService;
        this.locationReferenceService = locationReferenceService;
        this.campaignSummaries = campaignSummaries;
        this.clearingDates = clearingDates;
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

        // Ficha R-6, G-2: del event store al consultar, sin tocar la proyección (C-2)
        Optional<ClearingDates> dates = clearingDates.findClearingDates(model.fundId());
        PublicFinancialSnapshotDTO financialSnapshot = new PublicFinancialSnapshotDTO(
                model.currency(),
                model.originalAmount(),
                model.clearedAmount(),
                model.pendingAllocationAmount(),
                model.confirmedAllocationAmount(),
                model.refundedAmount(),
                dates.map(d -> d.firstClearedAt().toString()).orElse(null),
                dates.map(d -> d.lastClearedAt().toString()).orElse(null)
        );

        // Ficha R-6, G-4 (D-R6-5): un parentAssetRef solo apunta a otro ítem de esta misma logistics
        Set<String> assetIdsInResponse = model.logistics() != null
                ? model.logistics().stream().map(LogisticsReadItem::assetId).collect(Collectors.toSet())
                : Set.of();
        List<PublicLogisticsItemDTO> logistics = model.logistics() != null
                ? model.logistics().stream().map(item -> mapLogisticsItem(item, assetIdsInResponse)).toList()
                : List.of();

        // Ficha R-6, G-1: sin convocatoria, o si no se encuentra, no hay título ni organización (rama defensiva)
        Optional<CampaignPublicSummary> summary = model.campaignRef() == null
                ? Optional.empty()
                : campaignSummaries.findByCampaignRef(model.campaignRef());

        return new PublicDonationTrackingDTO(
                financialSnapshot,
                model.campaignRef(),
                logistics,
                publicStatus,
                summary.map(CampaignPublicSummary::title).orElse(null),
                summary.map(CampaignPublicSummary::organizationName).orElse(null)
        );
    }

    private PublicLogisticsItemDTO mapLogisticsItem(LogisticsReadItem item, Set<String> assetIdsInResponse) {
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
                custodianCategory,
                // Nunca se calcula el ref de un activo que no esté en la respuesta (plan del Bloque C, §2.1)
                item.parentAssetId() != null && assetIdsInResponse.contains(item.parentAssetId())
                        ? assetRefService.computeRef(item.parentAssetId())
                        : null
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
