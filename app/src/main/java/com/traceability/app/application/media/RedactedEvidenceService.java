package com.traceability.app.application.media;

import com.traceability.contracts.media.MediaStoragePort;
import com.github.f4b6a3.ulid.UlidCreator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * La copia censurada de la foto de entrega (ADR-051 Enmienda 2, E2-4, E2-4b, E2-5 y E2-7; plan de la Tarea 3):
 * <ul>
 *   <li><b>Generación bajo demanda y concurrencia:</b> busca la copia por {@code ownerRef} (la {@code objectKey} de la
 *   original); si no existe, genera los bytes, los guarda (F2: bytes primero, metadato después) e inserta. Dos peticiones
 *   simultáneas son un caso normal: el choque con el índice único por {@code ownerRef} significa «ya existe»; quien pierde
 *   <b>borra su archivo</b> (o lo registra si no puede) y relee la copia ganadora. Nunca un error.</li>
 *   <li><b>Publicación:</b> solo se añade al historial (una escritura condicional y atómica por decisión); las repetidas no
 *   añaden nada. La publicación <b>vigente</b> exige copia y original vivas.</li>
 *   <li><b>Retirada con la original:</b> primero se anula la publicación, después se borran los bytes y se marca la copia
 *   (la original se retira al final, así «original retirada» implica «copia ya resuelta» y un reintento es seguro).</li>
 * </ul>
 */
@Service
public class RedactedEvidenceService {

    private static final Logger log = LoggerFactory.getLogger(RedactedEvidenceService.class);

    /** La copia con sus bytes. */
    public record Copy(MediaObject object, byte[] bytes) {}

    private final MediaObjectStore metadata;
    private final MediaStoragePort storage;
    private final EvidenceRedactor redactor;
    private final Clock clock;

    @Autowired
    public RedactedEvidenceService(MediaObjectStore metadata, MediaStoragePort storage, EvidenceRedactor redactor,
                                   ObjectProvider<Clock> clock) {
        this.metadata = metadata;
        this.storage = storage;
        this.redactor = redactor;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    /** El metadato de la copia de esa original, si existe (también la retirada). */
    public Optional<MediaObject> find(MediaObject original) {
        return metadata.findByOrganizationIdAndPurposeAndOwnerRef(original.organizationId(),
                MediaPurpose.DELIVERY_EVIDENCE_REDACTED, original.objectKey());
    }

    /** La copia; la genera si no existe. Retirada → {@link EvidenceRemovedException} (410). */
    public Copy getOrGenerate(MediaObject original, String requestedBy) {
        Optional<MediaObject> existing = find(original);
        if (existing.isPresent()) {
            return read(existing.get());
        }
        byte[] source = storage.get(original.objectKey()).orElseThrow(DeliveryEvidenceUnavailableException::new);
        EvidenceRedactor.Redacted redacted;
        try {
            redacted = redactor.redact(source);
        } catch (IllegalArgumentException e) {
            throw new DeliveryEvidenceUnavailableException();
        }
        String objectKey = UlidCreator.getUlid().toString();
        storage.put(objectKey, redacted.png(), "image/png");
        MediaObject mediaObject = new MediaObject(objectKey, sha256(redacted.png()), MediaPurpose.DELIVERY_EVIDENCE_REDACTED,
                original.organizationId(), original.objectKey(), "image/png", redacted.png().length, redacted.width(),
                redacted.height(), requestedBy, clock.instant());
        try {
            metadata.insert(mediaObject);
            return new Copy(mediaObject, redacted.png());
        } catch (DuplicateRedactedCopyException e) {
            // perdió la carrera: su archivo queda huérfano; se intenta borrar y, si no se puede, se registra (F2, F10)
            discardOrphan(objectKey);
            return read(find(original).orElseThrow(() -> e));
        } catch (RuntimeException e) {
            discardOrphan(objectKey);
            throw e;
        }
    }

    private Copy read(MediaObject copy) {
        if (copy.deletedAt() != null) {
            throw new EvidenceRemovedException();
        }
        return new Copy(copy, storage.get(copy.objectKey()).orElseThrow(DeliveryEvidenceUnavailableException::new));
    }

    private void discardOrphan(String objectKey) {
        try {
            storage.delete(objectKey);
        } catch (RuntimeException failure) {
            log.warn("media.orphan objectKey={} purpose={} cause={}", objectKey, MediaPurpose.DELIVERY_EVIDENCE_REDACTED,
                    failure.getClass().getSimpleName());
        }
    }

    /**
     * Publica (E2-5; Q-T3-3 (a)): la copia tiene que existir ({@link RedactedCopyNotGeneratedException}, 409) y no estar
     * retirada ni ella ni la original (410). Ya publicada: sin entrada.
     */
    public void publish(MediaObject original, String by) {
        MediaObject copy = find(original).orElseThrow(RedactedCopyNotGeneratedException::new);
        if (copy.deletedAt() != null || original.deletedAt() != null) {
            throw new EvidenceRemovedException();
        }
        metadata.appendPublicationDecision(copy.objectKey(),
                new PublicationDecision(PublicationDecision.Action.PUBLISH, clock.instant(), by, Boolean.TRUE));
    }

    /** Despublica; sin copia, retirada o no publicada, no hace nada (y no añade entrada). */
    public void unpublish(MediaObject original, String by) {
        find(original).filter(copy -> copy.deletedAt() == null).ifPresent(copy -> metadata.appendPublicationDecision(
                copy.objectKey(), new PublicationDecision(PublicationDecision.Action.UNPUBLISH, clock.instant(), by, null)));
    }

    /**
     * La copia que se puede mostrar en el seguimiento público (ADR-021-D Enmienda 2, E5): publicada y con copia y original
     * vivas; en cualquier otro caso, vacío. No genera nada ni dice por qué falta (E7).
     */
    public Optional<Copy> publishedCopy(MediaObject original) {
        if (!publication(original).published()) {
            return Optional.empty();
        }
        return find(original).filter(copy -> copy.deletedAt() == null).map(this::read);
    }

    /** El historial de esa copia; {@code published} es la publicación VIGENTE (publicada y con copia y original vivas). */
    public Publication publication(MediaObject original) {
        Optional<MediaObject> copy = find(original);
        if (copy.isEmpty()) {
            return new Publication(false, List.of());
        }
        Publication stored = metadata.findPublication(copy.get().objectKey()).orElse(new Publication(false, List.of()));
        boolean alive = copy.get().deletedAt() == null && original.deletedAt() == null;
        return new Publication(stored.published() && alive, stored.decisions());
    }

    /**
     * La retirada F7 de la foto de entrega (E2-7), antes de retirar la original: (1) anula la publicación vigente con una
     * entrada {@code UNPUBLISH} de la plataforma, (2) borra los bytes de la copia y (3) la marca. Si falla (503), la
     * publicación ya quedó anulada y reintentar completa el resto sin repetir lo hecho (la anulación es condicional y el
     * borrado de bytes, idempotente). Sin copia, no hace nada.
     */
    public void retireCopyOf(MediaObject original, String platformAccount, String reason) {
        Optional<MediaObject> copy = find(original);
        if (copy.isEmpty() || copy.get().deletedAt() != null) {
            return;
        }
        metadata.appendPublicationDecision(copy.get().objectKey(),
                new PublicationDecision(PublicationDecision.Action.UNPUBLISH, clock.instant(), platformAccount, null));
        try {
            storage.delete(copy.get().objectKey());
        } catch (RuntimeException e) {
            log.warn("media.removal.failed objectKey={} cause={}", copy.get().objectKey(), e.getClass().getSimpleName());
            throw e;
        }
        metadata.markDeleted(copy.get().objectKey(), platformAccount, clock.instant(), reason);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
