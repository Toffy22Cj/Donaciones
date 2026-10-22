package com.traceability.app.application.media;

import java.time.Instant;
import java.util.Optional;

/**
 * Puerto de salida de los metadatos de imágenes (ADR-051 F9: {@code media_objects} es propiedad del adaptador de
 * {@code app}), con el patrón {@code application.port.out} de los demás módulos. Lo que usan
 * {@link MediaUploadService} y, desde la Tarea 2, la lectura y el borrado del logo (F6, F10).
 */
public interface MediaObjectStore {

    /**
     * @throws DuplicateEvidenceException si ya existe una evidencia con la misma {@code (organizationId, purpose,
     *                                    sha256)} (índice único parcial de F9)
     */
    void insert(MediaObject mediaObject);

    Optional<MediaObject> findByOrganizationIdAndPurposeAndSha256(String organizationId, MediaPurpose purpose,
                                                                  String sha256);

    /** También las borradas: {@link MediaObject#deletedAt()} lo dice. */
    Optional<MediaObject> findByObjectKey(String objectKey);

    /**
     * Borrado lógico de F10 (F9: {@code deletedAt}, {@code deletedBy}, {@code reason}). El metadato no se borra: es el
     * rastro de lo que se hizo. Sin metadato con esa clave, no hace nada.
     */
    void markDeleted(String objectKey, String deletedBy, Instant deletedAt, String reason);
}
