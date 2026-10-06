package com.traceability.convocatoria.application.port.out;

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
}
