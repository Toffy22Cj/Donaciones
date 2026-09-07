package com.traceability.api.application.mapper;

import com.traceability.api.application.dto.AssetHistoryPublicDTO;
import com.traceability.api.application.dto.PublicTransitionDTO;
import com.traceability.core.application.service.LocationReferenceService;
import com.traceability.core.application.port.out.AssetHistoryReadModel;
import com.traceability.core.application.port.out.AssetTransitionReadModel;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AssetHistoryMapper {

    private final LocationReferenceService locationReferenceService;
    private final PublicDonationMapper publicDonationMapper;

    public AssetHistoryMapper(LocationReferenceService locationReferenceService, PublicDonationMapper publicDonationMapper) {
        this.locationReferenceService = locationReferenceService;
        this.publicDonationMapper = publicDonationMapper;
    }

    public AssetHistoryPublicDTO toDto(AssetHistoryReadModel document) {
        if (document == null || document.transitions() == null) {
            return new AssetHistoryPublicDTO(List.of());
        }

        List<PublicTransitionDTO> transitions = document.transitions().stream()
                .map(this::toTransitionDto)
                .toList();

        return new AssetHistoryPublicDTO(transitions);
    }

    private PublicTransitionDTO toTransitionDto(AssetTransitionReadModel transition) {
        String zone = locationReferenceService.resolveZone(transition.location())
                .orElse(null);

        return new PublicTransitionDTO(
                transition.eventType(),
                transition.timestamp() != null ? transition.timestamp().toString() : null,
                zone,
                publicDonationMapper.mapCustodian(transition.custodian()),
                transition.status()
        );
    }
}
