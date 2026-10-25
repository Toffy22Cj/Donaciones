package com.traceability.convocatoria.application.query;

import com.traceability.convocatoria.application.port.out.ConvocatoriaRepositoryPort;
import com.traceability.convocatoria.domain.model.ConvocatoriaStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Cierres de las convocatorias de una organización, para su actividad reciente (ficha R-6, G-8, {@code CAMPAIGN_CLOSED};
 * plan D, P-R6D-2 (a), limitada a las cerradas). Se listan con {@code findByOrganizationRef}, se quedan las
 * {@code CLOSED} y solo de esas se lee la fecha de cierre con {@link CampaignTimelineQuery}: una lectura del registro de
 * auditoría por convocatoria cerrada. No cambia ningún puerto. Solo lectura; la autorización la hace quien llama.
 */
@Component
public class OrganizationCampaignClosuresQuery {

    /** Como el límite de los directorios de la organización (ficha R-6, Bloque B y D). */
    public static final int MAX_CAMPAIGNS = 10_000;

    public record CampaignClosure(String campaignRef, Instant closedAt) {}

    private final ConvocatoriaRepositoryPort convocatorias;
    private final CampaignTimelineQuery timelines;

    public OrganizationCampaignClosuresQuery(ConvocatoriaRepositoryPort convocatorias, CampaignTimelineQuery timelines) {
        this.convocatorias = convocatorias;
        this.timelines = timelines;
    }

    /** Una convocatoria {@code CLOSED} sin entrada de cierre en su registro (no debería pasar) no se lista. */
    public List<CampaignClosure> closedCampaigns(String organizationRef) {
        return convocatorias.findByOrganizationRef(organizationRef, MAX_CAMPAIGNS).stream()
                .filter(c -> c.getStatus() == ConvocatoriaStatus.CLOSED)
                .map(c -> {
                    Instant closedAt = timelines.timelineOf(c.getCampaignRef()).closedAt();
                    return closedAt == null ? null : new CampaignClosure(c.getCampaignRef(), closedAt);
                })
                .filter(Objects::nonNull)
                .toList();
    }
}
