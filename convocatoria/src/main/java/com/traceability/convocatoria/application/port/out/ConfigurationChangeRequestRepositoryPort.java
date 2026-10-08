package com.traceability.convocatoria.application.port.out;

import com.traceability.convocatoria.domain.model.ConfigurationChangeRequest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistencia de {@link ConfigurationChangeRequest} (Enmienda 4 de ADR-037, D2): colección
 * {@code configuration_change_requests}, con índice único parcial {@code {campaignRef} WHERE status = PENDING}.
 */
public interface ConfigurationChangeRequestRepositoryPort {

    /** Una segunda pendiente de la misma convocatoria lanza {@code ConfigurationChangeRequestAlreadyPendingException}. */
    void insert(ConfigurationChangeRequest request);

    Optional<ConfigurationChangeRequest> findById(String requestId);

    /** Las de una convocatoria, las más recientes primero, como mucho {@code limit}. */
    List<ConfigurationChangeRequest> findByCampaignRef(String campaignRef, int limit);

    /** Escritura condicional {@code PENDING → APPROVED}. Devuelve si se aplicó. */
    boolean markApprovedIfPending(String requestId, String decidedBy, Instant decidedAt, long resultingVersion);

    /** Escritura condicional {@code PENDING → REJECTED}. Devuelve si se aplicó. */
    boolean markRejectedIfPending(String requestId, String decidedBy, Instant decidedAt);
}
