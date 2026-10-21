package com.traceability.app.application.media;

import java.util.Optional;

/**
 * Puerto de salida de los metadatos de imágenes (ADR-051 F9: {@code media_objects} es propiedad del adaptador de
 * {@code app}), con el patrón {@code application.port.out} de los demás módulos. Solo lo que usa
 * {@link MediaUploadService}.
 */
public interface MediaObjectStore {

    /**
     * @throws DuplicateEvidenceException si ya existe una evidencia con la misma {@code (organizationId, purpose,
     *                                    sha256)} (índice único parcial de F9)
     */
    void insert(MediaObject mediaObject);

    Optional<MediaObject> findByOrganizationIdAndPurposeAndSha256(String organizationId, MediaPurpose purpose,
                                                                  String sha256);
}
