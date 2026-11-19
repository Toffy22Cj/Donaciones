package com.traceability.app.infrastructure.media;

import com.traceability.app.application.media.DuplicateEvidenceException;
import com.traceability.app.application.media.DuplicateRedactedCopyException;
import com.traceability.app.application.media.Publication;
import com.traceability.app.application.media.PublicationDecision;
import com.traceability.app.application.media.MediaObject;
import com.traceability.app.application.media.MediaObjectStore;
import com.traceability.app.application.media.MediaPurpose;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * {@link MediaObjectStore} sobre {@code media_objects} (ADR-051 F9: los metadatos son propiedad del adaptador de
 * {@code app}). Traduce el choque del índice único de la evidencia ({@value MediaObjectDocument#EVIDENCE_SHA256_INDEX})
 * a {@link DuplicateEvidenceException}; cualquier otro fallo de la base se propaga tal cual.
 */
@Component
public class MongoMediaObjectStore implements MediaObjectStore {

    private final MediaObjectRepository repository;
    private final MongoTemplate mongoTemplate;

    public MongoMediaObjectStore(MediaObjectRepository repository, MongoTemplate mongoTemplate) {
        this.repository = repository;
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void insert(MediaObject mediaObject) {
        try {
            repository.insert(new MediaObjectDocument(null, mediaObject.objectKey(), mediaObject.sha256(),
                    mediaObject.purpose(), mediaObject.organizationId(), mediaObject.ownerRef(),
                    mediaObject.contentType(), mediaObject.sizeBytes(), mediaObject.width(), mediaObject.height(),
                    mediaObject.uploadedBy(), mediaObject.uploadedAt(), null, null, null, null));
        } catch (DuplicateKeyException e) {
            String message = String.valueOf(e.getMessage());
            if (message.contains(MediaObjectDocument.EVIDENCE_SHA256_INDEX)
                    || message.contains(MediaObjectDocument.STEP_EVIDENCE_SHA256_INDEX)) {
                throw new DuplicateEvidenceException("A delivery evidence with the same bytes already exists");
            }
            // ADR-051 Enmienda 2, Tarea 3: la copia censurada de esa original ya existe («ya existe», nunca un error)
            if (message.contains(MediaObjectDocument.REDACTED_OWNER_INDEX)) {
                throw new DuplicateRedactedCopyException("The redacted copy of this original already exists");
            }
            throw e;
        }
    }

    @Override
    public Optional<MediaObject> findByOrganizationIdAndPurposeAndSha256(String organizationId, MediaPurpose purpose,
                                                                         String sha256) {
        return repository.findByOrganizationIdAndPurposeAndSha256(organizationId, purpose, sha256)
                .map(MongoMediaObjectStore::toMediaObject);
    }

    @Override
    public Optional<MediaObject> findByObjectKey(String objectKey) {
        return repository.findByObjectKey(objectKey).map(MongoMediaObjectStore::toMediaObject);
    }

    @Override
    public void markDeleted(String objectKey, String deletedBy, Instant deletedAt, String reason) {
        // sin metadato no hay nada que marcar (un archivo huérfano de F2 nunca tuvo metadato). Un update, no un guardado del
        // documento entero: así el historial de publicación (E2-5) nunca se pisa
        mongoTemplate.updateFirst(Query.query(Criteria.where("objectKey").is(objectKey)),
                new Update().set("deletedAt", deletedAt).set("deletedBy", deletedBy).set("reason", reason),
                MediaObjectDocument.class);
    }

    @Override
    public Optional<MediaObject> findByOrganizationIdAndPurposeAndOwnerRef(String organizationId, MediaPurpose purpose,
                                                                           String ownerRef) {
        return Optional.ofNullable(mongoTemplate.findOne(Query.query(Criteria.where("organizationId").is(organizationId)
                        .and("purpose").is(purpose).and("ownerRef").is(ownerRef)), MediaObjectDocument.class))
                .map(MongoMediaObjectStore::toMediaObject);
    }

    /**
     * Una sola escritura condicional y atómica por decisión (E2-5): {@code $push} al final del historial y el estado
     * derivado {@code published} en la MISMA operación. {@code PUBLISH} solo si no está borrada ni ya publicada;
     * {@code UNPUBLISH}, solo si está publicada. Sin coincidencia no se añade nada.
     */
    @Override
    public boolean appendPublicationDecision(String objectKey, PublicationDecision decision) {
        boolean publish = decision.action() == PublicationDecision.Action.PUBLISH;
        Criteria filter = Criteria.where("objectKey").is(objectKey).and("deletedAt").is(null);
        filter = publish ? filter.and("published").ne(true) : filter.and("published").is(true);
        Update update = new Update().push("publicationDecisions", decision).set("published", publish);
        return mongoTemplate.updateFirst(Query.query(filter), update, MediaObjectDocument.class).getModifiedCount() == 1;
    }

    @Override
    public Optional<Publication> findPublication(String objectKey) {
        return repository.findByObjectKey(objectKey).map(d -> new Publication(Boolean.TRUE.equals(d.published()),
                d.publicationDecisions() == null ? List.of() : List.copyOf(d.publicationDecisions())));
    }

    /**
     * Un {@code updateFirst} con el filtro dentro del índice único parcial {@value MediaObjectDocument#EVIDENCE_SHA256_INDEX}
     * ({@code purpose} incluido): {@code deletedAt} nulo y {@code boundAssetId} nulo o ese mismo activo. Dos entregas
     * simultáneas de activos distintos: solo una escritura coincide (ADR-051 F3, criterio 10).
     */
    @Override
    public BindResult bindEvidence(String organizationId, String sha256, String assetId) {
        return bindEvidence(organizationId, sha256, assetId, MediaPurpose.DELIVERY_EVIDENCE);
    }

    /** Lo mismo con el propósito del paso (ADR-051 Enmienda 2, E2-1); la entrega usa {@code DELIVERY_EVIDENCE}. */
    @Override
    public BindResult bindEvidence(String organizationId, String sha256, String assetId, MediaPurpose purpose) {
        Criteria evidence = Criteria.where("organizationId").is(organizationId)
                .and("purpose").is(purpose)
                .and("sha256").is(sha256);
        Query conditional = new Query(new Criteria().andOperator(evidence, Criteria.where("deletedAt").is(null),
                Criteria.where("boundAssetId").in(null, assetId)));
        if (mongoTemplate.updateFirst(conditional, new Update().set("boundAssetId", assetId), MediaObjectDocument.class)
                .getMatchedCount() == 1) {
            return BindResult.BOUND;
        }
        MediaObjectDocument found = mongoTemplate.findOne(new Query(evidence), MediaObjectDocument.class);
        return found != null && found.deletedAt() == null && found.boundAssetId() != null
                && !found.boundAssetId().equals(assetId) ? BindResult.BOUND_TO_OTHER : BindResult.NOT_FOUND;
    }

    private static MediaObject toMediaObject(MediaObjectDocument document) {
        return new MediaObject(document.objectKey(), document.sha256(), document.purpose(), document.organizationId(),
                document.ownerRef(), document.contentType(), document.sizeBytes(), document.width(), document.height(),
                document.uploadedBy(), document.uploadedAt(), document.deletedAt());
    }
}
