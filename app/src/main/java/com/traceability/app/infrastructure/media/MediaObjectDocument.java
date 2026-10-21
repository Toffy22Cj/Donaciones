package com.traceability.app.infrastructure.media;

import com.traceability.app.application.media.MediaPurpose;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Metadatos de una imagen guardada (ADR-051 F9), con los campos exactos de la tabla de {@code media_objects}. La imagen
 * nunca está aquí: solo su clave en el proveedor y su huella.
 * <p>
 * Índices, que {@code MongoIndexInitializer} crea al arrancar (H-IDX-1):
 * <ul>
 *   <li>{@value #OBJECT_KEY_INDEX}: único en {@code objectKey};</li>
 *   <li>{@value #EVIDENCE_SHA256_INDEX}: único {@code (organizationId, purpose, sha256)} <b>parcial</b>, solo para
 *       {@code DELIVERY_EVIDENCE}: subir dos veces la misma evidencia no duplica (F9), y un logo o una portada pueden
 *       repetir bytes (por ejemplo, el mismo logo subido otra vez tras reemplazarlo, F10).</li>
 * </ul>
 */
@Document(MediaObjectDocument.COLLECTION)
@CompoundIndex(name = MediaObjectDocument.EVIDENCE_SHA256_INDEX, def = "{'organizationId': 1, 'purpose': 1, 'sha256': 1}",
        unique = true, partialFilter = "{'purpose': 'DELIVERY_EVIDENCE'}")
public record MediaObjectDocument(
        @Id String id,
        @Indexed(name = MediaObjectDocument.OBJECT_KEY_INDEX, unique = true) String objectKey,
        String sha256,
        MediaPurpose purpose,
        String organizationId,
        String ownerRef,
        String contentType,
        long sizeBytes,
        int width,
        int height,
        String uploadedBy,
        Instant uploadedAt,
        String boundAssetId,
        Instant deletedAt,
        String deletedBy,
        String reason) {

    public static final String COLLECTION = "media_objects";
    public static final String OBJECT_KEY_INDEX = "uq_object_key";
    public static final String EVIDENCE_SHA256_INDEX = "uq_evidence_sha256";
}
