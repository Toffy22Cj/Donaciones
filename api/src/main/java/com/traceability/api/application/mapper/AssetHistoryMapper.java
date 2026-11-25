package com.traceability.api.application.mapper;

import com.traceability.api.application.dto.PublicCustodianCategory;

import com.traceability.api.application.dto.AssetHistoryPublicDTO;
import com.traceability.api.application.dto.PublicAnchorProofDTO;
import com.traceability.api.application.dto.PublicTransitionDTO;
import com.traceability.contracts.AssetAnchorStatusPort;
import com.traceability.contracts.AssetAnchorStatusPort.AnchorProof;
import com.traceability.contracts.AssetAnchorStatusPort.StepAnchor;
import com.traceability.contracts.TrackingAnchorStatus;
import com.traceability.core.application.service.PublicZoneResolver;
import com.traceability.core.application.port.out.AssetHistoryReadModel;
import com.traceability.core.application.port.out.AssetTransitionReadModel;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class AssetHistoryMapper {

    /** ADR-051 F3: el único formato que se expone (enmienda de ADR-021-D, E1). */
    private static final Pattern FOOTPRINT = Pattern.compile("^sha256:[0-9a-f]{64}$");

    /** La ruta relativa de la copia (E5); el {@code trackingCode} viaja solo en la cabecera, nunca aquí (E6). */
    private static final String PREVIEW_PATH = "/api/v1/donations/tracking/assets/%s/evidence-preview";

    private final PublicZoneResolver zones;
    private final PublicDonationMapper publicDonationMapper;
    private final AssetAnchorStatusPort anchors;

    public AssetHistoryMapper(PublicZoneResolver zones, PublicDonationMapper publicDonationMapper,
                              AssetAnchorStatusPort anchors) {
        this.zones = zones;
        this.publicDonationMapper = publicDonationMapper;
        this.anchors = anchors;
    }

    /** Sin copia que mostrar: el historial como era antes de la Tarea 4. */
    public AssetHistoryPublicDTO toDto(AssetHistoryReadModel document) {
        return toDto(document, null, false);
    }

    /**
     * @param assetRef         la referencia opaca del activo tal como llegó en la ruta (la que el cliente ya conoce); con ella se
     *                         arma {@code evidencePreviewUrl}. Nunca el {@code assetId} interno ni el {@code trackingCode}.
     * @param previewPublished lo que dijo {@code PublishedEvidencePreviewPort}: copia existente, publicada y original viva
     */
    public AssetHistoryPublicDTO toDto(AssetHistoryReadModel document, String assetRef, boolean previewPublished) {
        if (document == null || document.transitions() == null) {
            return new AssetHistoryPublicDTO(List.of());
        }

        // Ficha R-6, G-3 y Enmienda 1, E1: una lectura por historial; cada paso se cruza con su evento por sequence,
        // en el servidor (la sequence no sale por HTTP, DD-76)
        Map<Long, StepAnchor> anchorBySequence = anchors.anchorBySequence(document.assetId());
        // Enmienda 1 de ADR-021-D: la huella de la entrega llega en el mismo StepAnchor (H-T5-1, (a)), sin otra consulta
        List<PublicTransitionDTO> transitions = document.transitions().stream()
                .map(t -> {
                    StepAnchor anchor = anchorBySequence.get(t.sequence());
                    return toTransitionDto(t, anchor, anchor != null ? anchor.evidenceRef() : null, assetRef, previewPublished);
                })
                .toList();

        return new AssetHistoryPublicDTO(transitions);
    }

    private PublicTransitionDTO toTransitionDto(AssetTransitionReadModel transition, StepAnchor anchor,
                                                String evidenceRef, String assetRef, boolean previewPublished) {
        boolean delivery = "DELIVERED".equals(transition.status());
        // Enmienda 2 de ADR-021-D, E8 (Q-21D2-1 (a)): los cuatro pasos con foto posible llevan los campos de la huella
        boolean photoStep = delivery || "REGISTERED".equals(transition.status())
                || "DISPATCHED".equals(transition.status()) || "RECEIVED".equals(transition.status());
        boolean footprint = evidenceRef != null && FOOTPRINT.matcher(evidenceRef).matches();
        // ADR-029 Enmienda 2, D5: la zona sellada del paso; sin ella, el respaldo de location_reference
        String zone = zones.resolve(transition.zoneName(), transition.location()).orElse(null);

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
                photoStep ? Boolean.valueOf(evidenceRef != null && !evidenceRef.isBlank()) : null,
                // E2: el texto libre heredado puede llevar datos personales y nunca sale
                photoStep && footprint ? evidenceRef : null,
                // E5: solo en la entrega, con la copia publicada y con una original que exista (huella válida); sin referencia
                // para armar la ruta, ningún campo antes que una URL rota
                delivery && footprint && previewPublished && assetRef != null && !assetRef.isBlank()
                        ? PREVIEW_PATH.formatted(assetRef) : null
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
