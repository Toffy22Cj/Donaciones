package com.traceability.convocatoria.application.port.out;

import com.traceability.convocatoria.application.query.PublicCampaignDiscoveryQuery;
import com.traceability.convocatoria.domain.model.Convocatoria;

import java.util.Optional;

/**
 * Persistencia de {@link Convocatoria} (ADR-037 §2.1; Enmienda §3.2, §3.4; implementation_plan.md §4.2–§4.4).
 */
public interface ConvocatoriaRepositoryPort {

    void insert(Convocatoria convocatoria);

    Optional<Convocatoria> findByCampaignRef(String campaignRef);

    Optional<Convocatoria> findByPublicCode(String publicCode);

    /**
     * Escritura condicional sobre la versión esperada (Enmienda §3.2 [REQUISITO]): guarda la nueva configuración
     * y su versión solo si la versión persistida sigue siendo {@code expectedVersion} y el estado es {@code OPEN}.
     * Devuelve {@code false} si no se aplicó.
     */
    boolean updateConfigurationIfVersion(Convocatoria convocatoria, long expectedVersion);

    /** Escritura condicional {@code OPEN → CLOSED} (Enmienda §3.4). Devuelve si se aplicó. */
    boolean closeIfOpen(String campaignRef);

    /**
     * ADR-051, Tarea 3 (P-T3-1): fija ({@code coverObjectKey} no nulo) o quita (nulo) la portada en una sola escritura
     * atómica y devuelve la anterior. No toca ningún otro campo.
     */
    Optional<String> replaceCoverObjectKey(String campaignRef, String coverObjectKey);

    /** Convocatorias de una organización, ordenadas por {@code campaignRef}, como mucho {@code limit} (P2.3). */
    java.util.List<Convocatoria> findByOrganizationRef(String organizationRef, int limit);

    /**
     * Descubrimiento (P2.6; ficha R-1, que sustituye a {@code findPublicOpenAfter} según el plan R-1 §2): convocatorias
     * {@code PUBLIC} y {@code OPEN} que cumplen los filtros de {@code criteria}, en su orden, estrictamente después de
     * {@code criteria.after()} (o desde el principio si es {@code null}), como mucho {@code limit}. Nunca
     * {@code PRIVATE_LINK} ni {@code CLOSED}.
     */
    java.util.List<Convocatoria> findPublicOpenPage(PublicCampaignDiscoveryQuery.Criteria criteria, int limit);

    /** Convocatorias de la organización que empezaron antes de {@code before} (variable del predictor, P3). */
    long countByOrganizationStartedBefore(String organizationRef, java.time.Instant before);
}
