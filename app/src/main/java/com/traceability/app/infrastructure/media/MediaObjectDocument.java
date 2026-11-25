package com.traceability.app.infrastructure.media;

import com.traceability.app.application.media.MediaPurpose;
import com.traceability.app.application.media.PublicationDecision;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

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
@CompoundIndexes({
        @CompoundIndex(name = MediaObjectDocument.EVIDENCE_SHA256_INDEX, def = "{'organizationId': 1, 'purpose': 1, 'sha256': 1}",
                unique = true, partialFilter = "{'purpose': 'DELIVERY_EVIDENCE'}"),
        // ADR-051 Enmienda 2, Tarea 2 (plan §2.2): lo mismo para las fotos de registrar, despachar y recibir, SIN tocar el de
        // la entrega. La misma imagen en dos propósitos son dos objetos distintos (el propósito está en la clave).
        @CompoundIndex(name = MediaObjectDocument.STEP_EVIDENCE_SHA256_INDEX, def = "{'organizationId': 1, 'purpose': 1, 'sha256': 1}",
                unique = true,
                partialFilter = "{'purpose': {'$in': ['REGISTRATION_EVIDENCE', 'DISPATCH_EVIDENCE', 'RECEIPT_EVIDENCE']}}"),
        // ADR-051 Enmienda 2, Tarea 3 (plan §2.2, Q-T3-1): UNA copia por original, aunque la JDK diera bytes distintos. No hay
        // índice por huella para la copia: dos originales distintas pueden dar copias idénticas
        @CompoundIndex(name = MediaObjectDocument.REDACTED_OWNER_INDEX, def = "{'organizationId': 1, 'purpose': 1, 'ownerRef': 1}",
                unique = true, partialFilter = "{'purpose': 'DELIVERY_EVIDENCE_REDACTED'}"),
        // ADR-021-D Enmienda 2 (Tarea 4): la foto de entrega de un activo, por su vínculo; el seguimiento la busca en cada
        // historial, así que va por índice y no por barrido
        @CompoundIndex(name = MediaObjectDocument.BOUND_ASSET_INDEX, def = "{'boundAssetId': 1, 'purpose': 1}",
                partialFilter = "{'boundAssetId': {'$type': 'string'}}")
})
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
        String reason,
        List<PublicationDecision> publicationDecisions,
        Boolean published) {

    /** Sin historial de publicación: los documentos y las llamadas anteriores a la Tarea 3. */
    public MediaObjectDocument(String id, String objectKey, String sha256, MediaPurpose purpose, String organizationId,
                               String ownerRef, String contentType, long sizeBytes, int width, int height, String uploadedBy,
                               Instant uploadedAt, String boundAssetId, Instant deletedAt, String deletedBy, String reason) {
        this(id, objectKey, sha256, purpose, organizationId, ownerRef, contentType, sizeBytes, width, height, uploadedBy,
                uploadedAt, boundAssetId, deletedAt, deletedBy, reason, null, null);
    }

    public static final String COLLECTION = "media_objects";
    public static final String OBJECT_KEY_INDEX = "uq_object_key";
    public static final String BOUND_ASSET_INDEX = "idx_media_bound_asset";
    public static final String EVIDENCE_SHA256_INDEX = "uq_evidence_sha256";
    public static final String STEP_EVIDENCE_SHA256_INDEX = "uq_step_evidence_sha256";
    public static final String REDACTED_OWNER_INDEX = "uq_redacted_owner";
}
