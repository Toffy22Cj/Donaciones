package identity.domain.model;

import java.time.Instant;
import java.util.Map;

public record AuditLogEntry(
        String auditId,
        Instant occurredAt,
        AccountId actorAccountId,
        AccountId targetAccountId,
        OrganizationId targetOrganizationId,
        AuditAction action,
        Map<String, Object> changeSummary
) {
    public AuditLogEntry {
        if (auditId == null || auditId.isBlank()) {
            throw new IllegalArgumentException("Audit ID cannot be null or blank");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("OccurredAt cannot be null");
        }
        if (actorAccountId == null) {
            throw new IllegalArgumentException("ActorAccountId cannot be null");
        }
        if (action == null) {
            throw new IllegalArgumentException("AuditAction cannot be null");
        }
        if (changeSummary == null) {
            changeSummary = Map.of(); // default to empty
        }
    }
}
