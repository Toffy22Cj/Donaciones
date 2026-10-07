package com.traceability.api.application.mapper;

import com.traceability.api.application.dto.PublicCustodianCategory;

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
                custodianCategory(transition.status()),
                transition.status()
        );
    }

    /**
     * Categoría pública del custodio según el <b>estado</b> de la transición, como la logística (TR-01). Nunca la
     * referencia del custodio, que no se publica. Las transiciones que no son un estado del ciclo de vida ({@code SPLIT},
     * {@code SPLIT_COMPENSATED}, {@code CUSTODY_TRANSFERRED}) no tienen categoría propia: {@code UNCATEGORIZED}.
     * Hallazgo H-B6D-2: antes se pasaba la referencia del custodio y cualquier historial real fallaba.
     */
    private PublicCustodianCategory custodianCategory(String status) {
        if (status == null) {
            return PublicCustodianCategory.UNCATEGORIZED;
        }
        return switch (status) {
            case "REGISTERED", "DISPATCHED", "RECEIVED", "DELIVERED", "DEPLETED" -> publicDonationMapper.mapCustodian(status);
            default -> PublicCustodianCategory.UNCATEGORIZED;
        };
    }
}
