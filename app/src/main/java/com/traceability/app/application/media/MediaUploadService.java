package com.traceability.app.application.media;

import com.github.f4b6a3.ulid.UlidCreator;
import com.traceability.contracts.media.MediaStoragePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Optional;

/**
 * Subida de una imagen en el orden de ADR-051 F2, para que nunca exista un metadato que apunte a un archivo
 * inexistente:
 * <ol>
 *   <li>validar y limpiar en memoria ({@link ImageSanitizer}, F4);</li>
 *   <li>guardar los bytes con una clave aleatoria (ULID), sin datos de la organización;</li>
 *   <li>insertar el metadato en {@code media_objects} (F9), a través del puerto {@link MediaObjectStore}.</li>
 * </ol>
 * Si falla el guardado ({@code MediaStorageUnavailableException}, tras el reintento del adaptador), no se inserta nada.
 * Si falla el insert, el archivo queda huérfano: no causa daño y se registra con su clave (F2).
 * <p>
 * Evidencia de entrega (F9): los mismos bytes limpios de la misma organización devuelven la misma referencia sin
 * duplicar. Es de mejor esfuerzo, porque volver a codificar no es determinista entre versiones de la JDK (F4).
 */
@Service
public class MediaUploadService {

    private static final Logger log = LoggerFactory.getLogger(MediaUploadService.class);

    private final ImageSanitizer sanitizer;
    private final MediaStoragePort storage;
    private final MediaObjectStore metadata;
    private final Clock clock;

    @Autowired
    public MediaUploadService(ImageSanitizer sanitizer, MediaStoragePort storage, MediaObjectStore metadata,
                              ObjectProvider<Clock> clock) {
        this(sanitizer, storage, metadata, clock.getIfAvailable(Clock::systemUTC));
    }

    public MediaUploadService(ImageSanitizer sanitizer, MediaStoragePort storage, MediaObjectStore metadata,
                              Clock clock) {
        this.sanitizer = sanitizer;
        this.storage = storage;
        this.metadata = metadata;
        this.clock = clock;
    }

    /**
     * @param ownerRef el {@code campaignRef} de una portada; nulo en los demás propósitos (F9)
     * @throws ImageTooLargeException           tamaño o dimensiones por encima del máximo (F4, F8)
     * @throws UnsupportedImageFormatException  ni JPEG ni PNG, o una imagen que no se admite (F4)
     * @throws com.traceability.contracts.media.MediaStorageUnavailableException el proveedor no guardó (F2)
     */
    public StoredMedia store(MediaPurpose purpose, String organizationId, String ownerRef, String uploadedBy,
                             byte[] content) {
        if ((purpose == MediaPurpose.CAMPAIGN_COVER) == (ownerRef == null)) {
            throw new IllegalArgumentException("ownerRef is the campaignRef of a cover and null otherwise");
        }
        SanitizedImage image = sanitizer.sanitize(purpose, content);

        if (purpose == MediaPurpose.DELIVERY_EVIDENCE) {
            Optional<MediaObject> existing =
                    metadata.findByOrganizationIdAndPurposeAndSha256(organizationId, purpose, image.sha256());
            if (existing.isPresent()) {
                return toStoredMedia(existing.get());
            }
        }

        String objectKey = UlidCreator.getUlid().toString();
        storage.put(objectKey, image.content(), image.contentType());

        MediaObject mediaObject = new MediaObject(objectKey, image.sha256(), purpose, organizationId, ownerRef,
                image.contentType(), image.content().length, image.width(), image.height(), uploadedBy,
                clock.instant());
        try {
            metadata.insert(mediaObject);
            return toStoredMedia(mediaObject);
        } catch (DuplicateEvidenceException e) {
            // carrera de dos evidencias idénticas: gana el índice único; esta copia queda huérfana (P-8)
            logOrphan(objectKey, purpose, e);
            return metadata.findByOrganizationIdAndPurposeAndSha256(organizationId, purpose, image.sha256())
                    .map(MediaUploadService::toStoredMedia)
                    .orElseThrow(() -> e);
        } catch (RuntimeException e) {
            logOrphan(objectKey, purpose, e);
            throw e;
        }
    }

    /** ADR-051 F2: el huérfano se registra con su clave; ni el mensaje, ni la organización, ni quién subió. */
    private static void logOrphan(String objectKey, MediaPurpose purpose, Exception cause) {
        log.warn("media.orphan objectKey={} purpose={} cause={}", objectKey, purpose, cause.getClass().getSimpleName());
    }

    private static StoredMedia toStoredMedia(MediaObject object) {
        return new StoredMedia(object.objectKey(), object.sha256(), object.contentType(), object.sizeBytes(),
                object.width(), object.height());
    }
}
