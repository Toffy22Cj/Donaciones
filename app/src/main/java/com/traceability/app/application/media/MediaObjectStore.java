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

    /** Resultado de {@link #bindEvidence}. */
    enum BindResult { BOUND, NOT_FOUND, BOUND_TO_OTHER }

    /**
     * ADR-051 F3 (Tarea 4; P-T4-5): vincula la evidencia {@code (organizationId, DELIVERY_EVIDENCE, sha256)} sin borrar al
     * activo, en una sola escritura condicional ({@code boundAssetId} nulo o ya ese mismo activo). Si no se aplicó, una
     * lectura distingue {@link BindResult#BOUND_TO_OTHER} (viva y vinculada a otro activo) de
     * {@link BindResult#NOT_FOUND} (inexistente, de otra organización, que no es evidencia o borrada); la decisión ya la
     * tomó la escritura.
     */
    BindResult bindEvidence(String organizationId, String sha256, String assetId);
}
