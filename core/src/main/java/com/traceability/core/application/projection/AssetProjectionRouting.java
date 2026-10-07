package com.traceability.core.application.projection;

import com.traceability.core.application.port.out.EventStreamGenesisReadPort;
import com.traceability.core.domain.event.DomainEventPayload;
import com.traceability.core.domain.physicalasset.payloads.AssetRegisteredPayload;
import com.traceability.core.domain.physicalasset.payloads.AssetRegisteredV2Payload;
import com.traceability.core.domain.physicalasset.payloads.AssetRegisteredV3Payload;
import com.traceability.core.infrastructure.projection.mongo.documents.AssetIndexDocument;
import com.traceability.core.infrastructure.projection.mongo.documents.DonationProjectionDocument;
import com.traceability.core.infrastructure.projection.mongo.repositories.AssetIndexRepository;
import com.traceability.core.infrastructure.projection.mongo.repositories.DonationProjectionRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Enrutado de los eventos de {@code PhysicalAsset} hacia su proyección (B-PROJ, plan-b-proj.md §3.2). Única copia de
 * la lógica que antes repetían {@code DonationProjectionHandler}, {@code DonationAuditFactsHandler} y
 * {@code ProjectionEventSource}, ahora para las versiones 1 y 2 del registro.
 * <p>
 * <b>Camino B (donación en especie) — ignorado de forma explícita</b> hasta que se decida dónde se proyecta (Q3 de la
 * Enmienda 1 de ADR-029, con C5 de ADR-040): la {@code DonationProjection} se indexa por {@code fundId} y un activo en
 * especie no tiene {@code Fund}. La proyección es reconstruible, así que no se pierde nada.
 */
@Component
public class AssetProjectionRouting {

    /**
     * Vista común de {@code ASSET_REGISTERED} 1.0, 2.0 y 3.0. {@code campaignRef} solo existe en la 3.0 (ADR-029
     * Enmienda 1, D7); en 1.0/2.0 es {@code null} y nunca se infiere.
     */
    public record Registration(String assetId, String allocationId, String sourceAllocationId, String parentAssetRef,
                               String rootAssetRef, BigDecimal quantity, String unitOfMeasure, String assetType,
                               String currentLocation, String custodianRef, String donationRef, String campaignRef) {

        /**
         * Camino B: solo {@code PhysicalAsset.create} (donación en especie) fija {@code donationRef}; el Camino A
         * ({@code PhysicalAsset.register}) lo deja a {@code null}, y el hijo de una división lo hereda del padre. Un
         * registro 1.0 es siempre del Camino A.
         */
        public boolean isInKind() {
            return donationRef != null;
        }
    }

    private final AssetIndexRepository assetIndexRepository;
    private final DonationProjectionRepository projectionRepository;
    private final EventStreamGenesisReadPort genesisReadPort;

    public AssetProjectionRouting(AssetIndexRepository assetIndexRepository,
                                  DonationProjectionRepository projectionRepository,
                                  EventStreamGenesisReadPort genesisReadPort) {
        this.assetIndexRepository = assetIndexRepository;
        this.projectionRepository = projectionRepository;
        this.genesisReadPort = genesisReadPort;
    }

    /** Vista común si el payload es un {@code ASSET_REGISTERED} de cualquier versión. */
    public static Optional<Registration> registration(DomainEventPayload payload) {
        if (payload instanceof AssetRegisteredV3Payload p) {
            return Optional.of(new Registration(p.assetId(), p.allocationId(), p.sourceAllocationId(),
                    p.parentAssetRef(), p.rootAssetRef(), p.quantity(), p.unitOfMeasure(), p.assetType(),
                    p.currentLocation(), p.custodianRef(), p.donationRef(), p.campaignRef()));
        }
        if (payload instanceof AssetRegisteredV2Payload p) {
            return Optional.of(new Registration(p.assetId(), p.allocationId(), p.sourceAllocationId(),
                    p.parentAssetRef(), p.rootAssetRef(), p.quantity(), p.unitOfMeasure(), p.assetType(),
                    p.currentLocation(), p.custodianRef(), p.donationRef(), null));
        }
        if (payload instanceof AssetRegisteredPayload p) {
            return Optional.of(new Registration(p.assetId(), p.allocationId(), p.sourceAllocationId(),
                    p.parentAssetRef(), p.rootAssetRef(), p.quantity(), p.unitOfMeasure(), p.assetType(),
                    p.currentLocation(), p.custodianRef(), null, null));
        }
        return Optional.empty();
    }

    /**
     * Si el activo es del Camino B. Un activo ya indexado nunca lo es (solo se indexan los del Camino A); si no está
     * indexado y el evento no es su registro, se consulta su génesis en el event store.
     */
    public boolean isInKindAsset(String assetId, DomainEventPayload payload) {
        Optional<Registration> registration = registration(payload);
        if (registration.isPresent()) {
            return registration.get().isInKind();
        }
        if (assetIndexRepository.existsById(assetId)) {
            return false;
        }
        return genesisReadPort.findGenesisPayload(assetId)
                .flatMap(AssetProjectionRouting::registration)
                .map(Registration::isInKind)
                .orElse(false);
    }

    /**
     * {@code projectionId} (= {@code fundId}) del activo, o {@code null} si todavía no se puede resolver. No lanza
     * por sí mismo, salvo errores de acceso a datos: {@code ProjectionEventSource} lo usa al encolar reintentos.
     *
     * @param createIndex si escribe la entrada de {@code asset_index} al resolver un registro (solo el manejador de
     *                    la proyección, ADR-011)
     */
    public String resolveProjectionId(String assetId, DomainEventPayload payload, boolean createIndex) {
        Optional<AssetIndexDocument> index = assetIndexRepository.findById(assetId);
        if (index.isPresent()) {
            return index.get().getProjectionId();
        }
        Optional<Registration> registration = registration(payload);
        if (registration.isEmpty() || registration.get().isInKind()) {
            return null;
        }
        Registration r = registration.get();
        String projectionId;
        String rootAssetRef;
        if (r.parentAssetRef() == null) {
            if (r.allocationId() == null) {
                return null; // nunca consultar allocations.allocationId = null: casaría con proyecciones ajenas
            }
            projectionId = projectionRepository.findFirstByAllocationsAllocationId(r.allocationId())
                    .map(DonationProjectionDocument::getProjectionId).orElse(null);
            rootAssetRef = assetId;
        } else {
            Optional<AssetIndexDocument> parent = assetIndexRepository.findById(r.parentAssetRef());
            projectionId = parent.map(AssetIndexDocument::getProjectionId).orElse(null);
            rootAssetRef = parent.map(AssetIndexDocument::getRootAssetRef).orElse(null);
        }
        if (projectionId != null && createIndex) {
            assetIndexRepository.save(new AssetIndexDocument(assetId, projectionId, rootAssetRef, 0));
        }
        return projectionId;
    }
}
