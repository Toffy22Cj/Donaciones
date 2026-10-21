package com.traceability.app.infrastructure.media;

import com.traceability.app.application.media.DuplicateEvidenceException;
import com.traceability.app.application.media.MediaObject;
import com.traceability.app.application.media.MediaObjectStore;
import com.traceability.app.application.media.MediaPurpose;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * {@link MediaObjectStore} sobre {@code media_objects} (ADR-051 F9: los metadatos son propiedad del adaptador de
 * {@code app}). Traduce el choque del índice único de la evidencia ({@value MediaObjectDocument#EVIDENCE_SHA256_INDEX})
 * a {@link DuplicateEvidenceException}; cualquier otro fallo de la base se propaga tal cual.
 */
@Component
public class MongoMediaObjectStore implements MediaObjectStore {

    private final MediaObjectRepository repository;

    public MongoMediaObjectStore(MediaObjectRepository repository) {
        this.repository = repository;
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

    private static MediaObject toMediaObject(MediaObjectDocument document) {
        return new MediaObject(document.objectKey(), document.sha256(), document.purpose(), document.organizationId(),
                document.ownerRef(), document.contentType(), document.sizeBytes(), document.width(), document.height(),
                document.uploadedBy(), document.uploadedAt());
    }
}
