package com.traceability.convocatoria.application.service;

import com.traceability.convocatoria.application.authorization.ConvocatoriaAuthorizationPolicy;
import com.traceability.convocatoria.application.port.out.ConvocatoriaRepositoryPort;
import com.traceability.convocatoria.domain.exception.CampaignNotFoundException;
import com.traceability.convocatoria.domain.model.Convocatoria;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;

/**
 * ADR-051, Tarea 3 (plan de cierre §T3; P-T3-1): la referencia de la portada en la convocatoria (F9). Solo el
 * {@code ADMINISTRATOR} de la organización de la convocatoria la cambia (C1 (a), ADR-037 §5). Una convocatoria que no
 * existe o que no es de la organización de la ruta da {@link CampaignNotFoundException}, el mismo 403 que el resto
 * (DD-01). La portada no es configuración: se cambia también en una convocatoria {@code CLOSED} (P-T3-3). Sin
 * auditoría propia: el metadato de {@code media_objects} ya guarda quién y cuándo (P-T3-5).
 */
@Service
public class ConvocatoriaCoverReferenceService {

    private final ConvocatoriaRepositoryPort convocatorias;
    private final ConvocatoriaAuthorizationPolicy authorization;

    public ConvocatoriaCoverReferenceService(ConvocatoriaRepositoryPort convocatorias,
                                             ConvocatoriaAuthorizationPolicy authorization) {
        this.convocatorias = convocatorias;
        this.authorization = authorization;
    }

    /**
     * El permiso, para que {@code app} lo compruebe antes de leer los bytes (F8).
     *
     * @return el {@code publicCode} de la convocatoria, para la URL pública de la portada
     */
    public String requireCoverManager(String actorAccountId, String organizationId, String campaignRef) {
        Objects.requireNonNull(organizationId, "organizationId");
        Convocatoria c = convocatorias.findByCampaignRef(campaignRef)
                .filter(found -> organizationId.equals(found.getOrganizationRef()))
                .orElseThrow(() -> new CampaignNotFoundException("Campaign " + campaignRef + " not found"));
        authorization.requireAdministratorOf(actorAccountId, c.getOrganizationRef());
        return c.getPublicCode();
    }

    /** Fija la portada nueva y devuelve la anterior: <b>el cambio confirmado</b> de F10. */
    public Optional<String> replace(String actorAccountId, String organizationId, String campaignRef, String coverObjectKey) {
        Objects.requireNonNull(coverObjectKey, "coverObjectKey");
        requireCoverManager(actorAccountId, organizationId, campaignRef);
        return convocatorias.replaceCoverObjectKey(campaignRef, coverObjectKey);
    }

    /** Quita la portada y devuelve la anterior; sin portada, vacío y nada cambia. */
    public Optional<String> remove(String actorAccountId, String organizationId, String campaignRef) {
        requireCoverManager(actorAccountId, organizationId, campaignRef);
        return convocatorias.replaceCoverObjectKey(campaignRef, null);
    }
}
