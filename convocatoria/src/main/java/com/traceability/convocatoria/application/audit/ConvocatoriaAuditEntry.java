package com.traceability.convocatoria.application.audit;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Entrada append-only del audit log (ADR-037 §6). {@code actorRef} es la representación de actor propia del
 * módulo, derivada de {@code AuthorizationPrincipal} (nunca {@code core.domain.event.ActorRef}).
 * En la creación pública de {@code DonationIntent}, {@code actorRef} es el {@code donorRef} opaco (ADR-037 §5).
 * {@code selfAssigned} marca la autoasignación (Enmienda §4.3 [REQUISITO]).
 */
public record ConvocatoriaAuditEntry(
        String entryId,
        ConvocatoriaAuditAction action,
        String campaignRef,
        String actorRef,
        String targetRef,
        boolean selfAssigned,
        String commandId,
        Instant occurredAt,
        Map<String, String> details
) {

    public ConvocatoriaAuditEntry {
        Objects.requireNonNull(entryId, "entryId");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(campaignRef, "campaignRef");
        Objects.requireNonNull(occurredAt, "occurredAt");
        details = details == null ? Map.of() : Map.copyOf(details);
    }
}
