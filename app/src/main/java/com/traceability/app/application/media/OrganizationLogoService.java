package com.traceability.app.application.media;

import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.contracts.media.MediaStoragePort;
import com.traceability.convocatoria.application.query.ConvocatoriaReadPort;
import identity.application.service.OrganizationLogoReferenceQuery;
import identity.application.service.OrganizationLogoReferenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Clock;

/**
 * Logo de la organización (ADR-051, Tarea 2; plan §4.2 y §4.3): subir, reemplazar, quitar y leer.
 * <p>
 * Orden al subir: permiso (F8) → límite por propósito en el controlador (P-1) → {@link MediaUploadService} (F2, F4) →
 * {@code identity} fija la referencia nueva y devuelve la anterior (<b>el cambio confirmado</b>) → solo entonces se
 * borra el archivo anterior (F10). Si falla {@code identity}, el archivo nuevo queda huérfano y registrado (F2); si
 * falla el borrado del anterior, la respuesta no cambia y el anterior queda huérfano y registrado (criterio 16).
 */
@Service
public class OrganizationLogoService {

    /** {@code reason} del metadato cuando F10 borra un logo (P-T2-3). */
    public static final String REPLACED = "REPLACED";
    public static final String REMOVED = "REMOVED";

    /** Los bytes guardados, con su tipo y su huella (F6: {@code ETag} con la huella). */
    public record Logo(byte[] content, String contentType, String sha256) {}

    private static final Logger log = LoggerFactory.getLogger(OrganizationLogoService.class);

    private final OrganizationLogoReferenceService references;
    private final OrganizationLogoReferenceQuery referenceReads;
    private final MediaUploadService uploads;
    private final MediaStoragePort storage;
    private final MediaObjectStore metadata;
    private final ConvocatoriaReadPort campaigns;
    private final Clock clock;

    public OrganizationLogoService(OrganizationLogoReferenceService references,
                                   OrganizationLogoReferenceQuery referenceReads, MediaUploadService uploads,
                                   MediaStoragePort storage, MediaObjectStore metadata, ConvocatoriaReadPort campaigns,
                                   ObjectProvider<Clock> clock) {
        this.references = references;
        this.referenceReads = referenceReads;
        this.uploads = uploads;
        this.storage = storage;
        this.metadata = metadata;
        this.campaigns = campaigns;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    /** F8: la URL autenticada del logo, relativa a la raíz de la API (§2). */
    public static String logoUrl(String organizationId) {
        return "/api/v1/organizations/" + organizationId + "/logo";
    }

    /** §2: la URL pública, por el código de la convocatoria; nunca con el {@code organizationId} (Enmienda 1). */
    public static String publicLogoUrl(String publicCode) {
        return "/api/v1/public/campaigns/" + publicCode + "/organization-logo";
    }

    /** Paso 1 de §4.2: lo llama el controlador antes de mirar el tamaño (P-1). */
    public void requireLogoManager(AuthorizationPrincipal principal, String organizationId) {
        references.requireLogoManager(principal, organizationId);
    }

    /** Pasos 3 a 6 de §4.2. @return la URL del logo */
    public String replace(AuthorizationPrincipal principal, String organizationId, byte[] content) {
        references.requireLogoManager(principal, organizationId);
        StoredMedia stored = uploads.store(MediaPurpose.ORGANIZATION_LOGO, organizationId, null, principal.accountId(),
                content);
        String previous;
        try {
            previous = references.replace(principal, organizationId, stored.objectKey()).orElse(null);
        } catch (RuntimeException e) {
            logOrphan(stored.objectKey(), e);
            throw e;
        }
        if (previous != null) {
            discard(previous, principal.accountId(), REPLACED);
        }
        return logoUrl(organizationId);
    }

    /** {@code DELETE}: sin logo previo, no toca el proveedor (P-T2-4). */
    public void remove(AuthorizationPrincipal principal, String organizationId) {
        references.remove(principal, organizationId)
                .ifPresent(previous -> discard(previous, principal.accountId(), REMOVED));
    }

    /** Ruta pública (Enmienda 1): el logo de la organización dueña de la convocatoria visible por ese código. */
    public Logo publicLogo(String publicCode) {
        String organizationRef = campaigns.findPublicByCode(publicCode)
                .orElseThrow(OrganizationLogoNotFoundException::new).organizationRef();
        return load(referenceReads.logoObjectKeyOf(organizationRef).orElseThrow(OrganizationLogoNotFoundException::new));
    }

    /** Ruta autenticada (Enmienda 1): miembro de la organización o administrador de plataforma (P-T2-2). */
    public Logo memberLogo(AuthorizationPrincipal principal, String organizationId) {
        return load(referenceReads.logoObjectKeyForReader(principal, organizationId)
                .orElseThrow(OrganizationLogoNotFoundException::new));
    }

    /** Un logo con {@code deletedAt} o sin bytes nunca se sirve (criterio 14, en lo que toca al logo). */
    private Logo load(String objectKey) {
        MediaObject object = metadata.findByObjectKey(objectKey)
                .filter(o -> o.deletedAt() == null)
                .orElseThrow(OrganizationLogoNotFoundException::new);
        byte[] content = storage.get(objectKey).orElseThrow(OrganizationLogoNotFoundException::new);
        return new Logo(content, object.contentType(), object.sha256());
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

    /** ADR-051 F2, como {@link MediaUploadService}: la clave y la clase de la causa; nada de la organización. */
    private static void logOrphan(String objectKey, Exception cause) {
        log.warn("media.orphan objectKey={} purpose={} cause={}", objectKey, MediaPurpose.ORGANIZATION_LOGO,
                cause.getClass().getSimpleName());
    }
}
