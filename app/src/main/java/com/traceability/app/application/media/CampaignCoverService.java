package com.traceability.app.application.media;

import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.contracts.media.MediaStoragePort;
import com.traceability.convocatoria.application.query.ConvocatoriaReadPort;
import com.traceability.convocatoria.application.service.ConvocatoriaCoverReferenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Clock;

/**
 * Portada de la convocatoria (ADR-051, Tarea 3; plan de cierre §T3): subir, reemplazar, quitar y leer. Calca
 * {@link OrganizationLogoService}.
 * <p>
 * Orden al subir: permiso (F8, solo {@code ADMINISTRATOR}, C1 (a)) → límite por propósito en el controlador →
 * {@link MediaUploadService} (F2, F4) → {@code convocatoria} fija la referencia nueva y devuelve la anterior (<b>el
 * cambio confirmado</b>) → solo entonces se borra el archivo anterior (F10). Si falla {@code convocatoria}, el archivo
 * nuevo queda huérfano y registrado (F2); si falla el borrado del anterior, la respuesta no cambia y el anterior queda
 * huérfano y registrado (criterio 16).
 */
@Service
public class CampaignCoverService {

    /** Los bytes guardados, con su tipo y su huella (F6: {@code ETag} con la huella). */
    public record Cover(byte[] content, String contentType, String sha256) {}

    private static final Logger log = LoggerFactory.getLogger(CampaignCoverService.class);

    private final ConvocatoriaCoverReferenceService references;
    private final MediaUploadService uploads;
    private final MediaStoragePort storage;
    private final MediaObjectStore metadata;
    private final ConvocatoriaReadPort campaigns;
    private final Clock clock;

    public CampaignCoverService(ConvocatoriaCoverReferenceService references, MediaUploadService uploads,
                                MediaStoragePort storage, MediaObjectStore metadata, ConvocatoriaReadPort campaigns,
                                ObjectProvider<Clock> clock) {
        this.references = references;
        this.uploads = uploads;
        this.storage = storage;
        this.metadata = metadata;
        this.campaigns = campaigns;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    /** F6: la URL pública de la portada, por el código de la convocatoria; nunca con identificadores internos. */
    public static String coverUrl(String publicCode) {
        return "/api/v1/public/campaigns/" + publicCode + "/cover";
    }

    /** Lo llama el controlador antes de mirar el tamaño y de leer los bytes (F8). */
    public void requireCoverManager(AuthorizationPrincipal principal, String organizationId, String campaignRef) {
        references.requireCoverManager(principal.accountId(), organizationId, campaignRef);
    }

    /** @return la URL pública de la portada */
    public String replace(AuthorizationPrincipal principal, String organizationId, String campaignRef, byte[] content) {
        String publicCode = references.requireCoverManager(principal.accountId(), organizationId, campaignRef);
        StoredMedia stored = uploads.store(MediaPurpose.CAMPAIGN_COVER, organizationId, campaignRef,
                principal.accountId(), content);
        String previous;
        try {
            previous = references.replace(principal.accountId(), organizationId, campaignRef, stored.objectKey())
                    .orElse(null);
        } catch (RuntimeException e) {
            logOrphan(stored.objectKey(), e);
            throw e;
        }
        if (previous != null) {
            discard(previous, principal.accountId(), OrganizationLogoService.REPLACED);
        }
        return coverUrl(publicCode);
    }

    /** {@code DELETE}: sin portada previa, no toca el proveedor. */
    public void remove(AuthorizationPrincipal principal, String organizationId, String campaignRef) {
        references.remove(principal.accountId(), organizationId, campaignRef)
                .ifPresent(previous -> discard(previous, principal.accountId(), OrganizationLogoService.REMOVED));
    }

    /** Ruta pública: la portada de la convocatoria visible por ese código. */
    public Cover publicCover(String publicCode) {
        String objectKey = campaigns.findPublicByCode(publicCode)
                .map(v -> v.coverObjectKey())
                .orElseThrow(CampaignCoverNotFoundException::new);
        if (objectKey == null) {
            throw new CampaignCoverNotFoundException();
        }
        MediaObject object = metadata.findByObjectKey(objectKey)
                .filter(o -> o.deletedAt() == null)
                .orElseThrow(CampaignCoverNotFoundException::new);
        byte[] content = storage.get(objectKey).orElseThrow(CampaignCoverNotFoundException::new);
        return new Cover(content, object.contentType(), object.sha256());
    }

    /** F10, después del cambio confirmado: bytes fuera del proveedor y metadato marcado. Un fallo no cambia la respuesta. */
    private void discard(String objectKey, String deletedBy, String reason) {
        try {
            storage.delete(objectKey);
            metadata.markDeleted(objectKey, deletedBy, clock.instant(), reason);
        } catch (RuntimeException e) {
            logOrphan(objectKey, e);
        }
    }

    /** ADR-051 F2: la clave y la clase de la causa; nada de la organización ni de la convocatoria. */
    private static void logOrphan(String objectKey, Exception cause) {
        log.warn("media.orphan objectKey={} purpose={} cause={}", objectKey, MediaPurpose.CAMPAIGN_COVER,
                cause.getClass().getSimpleName());
    }
}
