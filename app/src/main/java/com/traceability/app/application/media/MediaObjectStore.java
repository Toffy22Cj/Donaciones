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

    /**
     * ADR-051 Enmienda 2, E2-1: lo mismo para la foto opcional de otro paso: la escritura condicional sobre
     * {@code (organizationId, purpose, sha256)}. Una foto de otro propósito no existe para este (NOT_FOUND). El método de
     * tres argumentos es el de la entrega ({@code DELIVERY_EVIDENCE}) y no cambia.
     */
    BindResult bindEvidence(String organizationId, String sha256, String assetId, MediaPurpose purpose);

    /**
     * ADR-051 Enmienda 2, E2-4b: la copia censurada de esa original (su {@code ownerRef} es la {@code objectKey} de la
     * original). También las borradas: {@link MediaObject#deletedAt()} lo dice.
     */
    Optional<MediaObject> findByOrganizationIdAndPurposeAndOwnerRef(String organizationId, MediaPurpose purpose, String ownerRef);

    /**
     * E2-5: añade UNA decisión al final del historial de la copia, en una sola escritura condicional y atómica, y dice si se
     * aplicó. {@code PUBLISH} solo si la copia no está borrada y no está ya publicada; {@code UNPUBLISH}, solo si está
     * publicada. Si no coincide, <b>no añade nada</b> (las repetidas son idempotentes y no ensucian el historial). El almacén
     * no ofrece ninguna operación que reemplace o borre entradas.
     */
    boolean appendPublicationDecision(String objectKey, PublicationDecision decision);

    /** El historial de esa copia y su estado actual; vacío si no existe el metadato. */
    Optional<Publication> findPublication(String objectKey);
}