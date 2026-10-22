package com.traceability.convocatoria.application.query;

import com.traceability.convocatoria.application.port.out.ConvocatoriaRepositoryPort;
import com.traceability.convocatoria.domain.model.Convocatoria;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Título de una convocatoria y su organización, por su {@code campaignRef}, para el seguimiento del donante (ficha
 * R-6, G-1; plan del Bloque A, P-R6A-1 (b), Manuel, 2026-10-21). Solo lectura y sin autorización: quien pregunta ya
 * tiene el {@code trackingCode} de una donación a esa convocatoria. No cambia ningún puerto existente.
 */
@Component
public class CampaignTitleQuery {

    /** {@code organizationRef} no sale de {@code app}: allí se convierte en el nombre público. */
    public record CampaignTitle(String title, String organizationRef) {}

    private final ConvocatoriaRepositoryPort convocatorias;

    public CampaignTitleQuery(ConvocatoriaRepositoryPort convocatorias) {
        this.convocatorias = convocatorias;
    }

    /** Vacío si la convocatoria no existe. */
    public Optional<CampaignTitle> findByCampaignRef(String campaignRef) {
        return convocatorias.findByCampaignRef(campaignRef).map(CampaignTitleQuery::title);
    }

    private static CampaignTitle title(Convocatoria c) {
        return new CampaignTitle(c.getTitle(), c.getOrganizationRef());
    }
}
