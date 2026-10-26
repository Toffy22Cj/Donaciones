package com.traceability.api.application.mapper;

import com.traceability.api.application.dto.PublicCustodianCategory;

import com.traceability.api.application.dto.AssetHistoryPublicDTO;
import com.traceability.api.application.dto.PublicAnchorProofDTO;
import com.traceability.api.application.dto.PublicTransitionDTO;
import com.traceability.contracts.AssetAnchorStatusPort;
import com.traceability.core.application.port.out.StreamEventAnchorDataPort;
import com.traceability.core.application.port.out.StreamEventAnchorDataPort.StreamEventAnchorData;
import org.springframework.beans.factory.annotation.Autowired;
import com.traceability.contracts.AssetAnchorStatusPort.AnchorProof;
import com.traceability.contracts.AssetAnchorStatusPort.StepAnchor;
import com.traceability.contracts.TrackingAnchorStatus;
import com.traceability.core.application.service.LocationReferenceService;
import com.traceability.core.application.port.out.AssetHistoryReadModel;
import com.traceability.core.application.port.out.AssetTransitionReadModel;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class AssetHistoryMapper {

    /** ADR-051 F3: el único formato que se expone (enmienda de ADR-021-D, E1). */
    private static final Pattern FOOTPRINT = Pattern.compile("^sha256:[0-9a-f]{64}$");

    private final LocationReferenceService locationReferenceService;
    private final PublicDonationMapper publicDonationMapper;
    private final AssetAnchorStatusPort anchors;
    private final StreamEventAnchorDataPort events;

    /** Sin la huella de la entrega, como antes de la Tarea 5 (lo usan los tests que no la necesitan). */
    public AssetHistoryMapper(LocationReferenceService locationReferenceService, PublicDonationMapper publicDonationMapper,
                              AssetAnchorStatusPort anchors) {
        this(locationReferenceService, publicDonationMapper, anchors, streamId -> List.of());
    }

    /** ADR-051, Tarea 5 (P-T5-1): la huella sale del Event Store, del mismo stream que el anclaje. */
    @Autowired
    public AssetHistoryMapper(LocationReferenceService locationReferenceService, PublicDonationMapper publicDonationMapper,
                              AssetAnchorStatusPort anchors, StreamEventAnchorDataPort events) {
        this.locationReferenceService = locationReferenceService;
        this.publicDonationMapper = publicDonationMapper;
        this.anchors = anchors;
        this.events = events;
    }

    public AssetHistoryPublicDTO toDto(AssetHistoryReadModel document) {
        if (document == null || document.transitions() == null) {
            return new AssetHistoryPublicDTO(List.of());
        }

        // Ficha R-6, G-3 y Enmienda 1, E1: una lectura por historial; cada paso se cruza con su evento por sequence,
        // en el servidor (la sequence no sale por HTTP, DD-76)
        Map<Long, StepAnchor> anchorBySequence = anchors.anchorBySequence(document.assetId());
        // Enmienda 1 de ADR-021-D: el evidenceRef de la entrega, por sequence, del Event Store
        Map<Long, String> evidenceBySequence = new HashMap<>();
        for (StreamEventAnchorData e : events.findByStream(document.assetId())) {
            if (e.evidenceRef() != null) {
                evidenceBySequence.put(e.sequence(), e.evidenceRef());
            }
        }
        List<PublicTransitionDTO> transitions = document.transitions().stream()
                .map(t -> toTransitionDto(t, anchorBySequence.get(t.sequence()), evidenceBySequence.get(t.sequence())))
                .toList();

        return new AssetHistoryPublicDTO(transitions);
    }

    private PublicTransitionDTO toTransitionDto(AssetTransitionReadModel transition, StepAnchor anchor,
                                                String evidenceRef) {
        // E1: solo el paso de la entrega lleva los campos de la evidencia
        boolean delivery = "DELIVERED".equals(transition.status());
        String zone = locationReferenceService.resolveZone(transition.location())
                .orElse(null);

        return new PublicTransitionDTO(
                transition.eventType(),
                transition.timestamp() != null ? transition.timestamp().toString() : null,
                zone,
                custodianCategory(transition.status()),
                transition.status(),
                // Un paso sin evento en el mapa es PENDING (P-R6C-3), sin hash: no hay evento del que leerlo (P-C-4)
                (anchor != null ? anchor.status() : TrackingAnchorStatus.PENDING).name(),
                anchor != null ? anchor.eventHash() : null,
                anchor != null ? toProofDto(anchor.proof()) : null,
                delivery ? Boolean.valueOf(evidenceRef != null && !evidenceRef.isBlank()) : null,
                // E2: el texto libre heredado puede llevar datos personales y nunca sale
                delivery && evidenceRef != null && FOOTPRINT.matcher(evidenceRef).matches() ? evidenceRef : null
        );
    }

    private static PublicAnchorProofDTO toProofDto(AnchorProof proof) {
        if (proof == null) {
            return null;
        }
        return new PublicAnchorProofDTO(proof.merkleRoot(), proof.leafIndex(), proof.path(), proof.transactionHash(),
                proof.network(), proof.anchoredAt() != null ? proof.anchoredAt().toString() : null);
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
