package com.traceability.app.infrastructure.media;

import com.traceability.app.application.media.MediaPurpose;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

/** Acceso a {@code media_objects} (ADR-051 F9), colección propiedad del adaptador de {@code app}. */
public interface MediaObjectRepository extends MongoRepository<MediaObjectDocument, String> {

    Optional<MediaObjectDocument> findByOrganizationIdAndPurposeAndSha256(String organizationId, MediaPurpose purpose,
                                                                          String sha256);

    Optional<MediaObjectDocument> findByObjectKey(String objectKey);
}
