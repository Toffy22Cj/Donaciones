package com.traceability.app.infrastructure.media;

import com.traceability.app.application.media.DuplicateEvidenceException;
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
            if (String.valueOf(e.getMessage()).contains(MediaObjectDocument.EVIDENCE_SHA256_INDEX)) {
                throw new DuplicateEvidenceException("A delivery evidence with the same bytes already exists");
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
        // sin metadato no hay nada que marcar (un archivo huérfano de F2 nunca tuvo metadato)
        repository.findByObjectKey(objectKey).ifPresent(d -> repository.save(new MediaObjectDocument(d.id(),
                d.objectKey(), d.sha256(), d.purpose(), d.organizationId(), d.ownerRef(), d.contentType(), d.sizeBytes(),
                d.width(), d.height(), d.uploadedBy(), d.uploadedAt(), d.boundAssetId(), deletedAt, deletedBy, reason)));
    }

    /**
     * Un {@code updateFirst} con el filtro dentro del índice único parcial {@value MediaObjectDocument#EVIDENCE_SHA256_INDEX}
     * ({@code purpose} incluido): {@code deletedAt} nulo y {@code boundAssetId} nulo o ese mismo activo. Dos entregas
     * simultáneas de activos distintos: solo una escritura coincide (ADR-051 F3, criterio 10).
     */
    @Override
    public BindResult bindEvidence(String organizationId, String sha256, String assetId) {
        Criteria evidence = Criteria.where("organizationId").is(organizationId)
                .and("purpose").is(MediaPurpose.DELIVERY_EVIDENCE)
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
