package identity.domain.model;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable append-only audit log entry for the identity module.
 *
 * @param legacyRecordedActorAccountId valor registrado por el código anterior al corte; NO es evidencia de quién ejecutó la acción (ADR-038 §2.2).
 */
public record AuditLogEntry(
        String auditId,
        Instant occurredAt,
        AuditActor actor,
        AccountId legacyRecordedActorAccountId,
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
        if (action == null) {
            throw new IllegalArgumentException("AuditAction cannot be null");
        }
        if ((actor == null && legacyRecordedActorAccountId == null) ||
            (actor != null && legacyRecordedActorAccountId != null)) {
            throw new IllegalArgumentException("Exactly one of actor or legacyRecordedActorAccountId must be non-null");
        }
        changeSummary = changeSummary != null ? changeSummary : Map.of();
    }

    public AuditRegime regime() {
        return actor != null ? AuditRegime.POST_CUTOVER : AuditRegime.PRE_CUTOVER;
    }

    public static AuditLogEntry record(
            String auditId,
            Instant occurredAt,
            AuditActor actor,
            AccountId targetAccountId,
            OrganizationId targetOrganizationId,
            AuditAction action,
            Map<String, Object> changeSummary
    ) {
        Objects.requireNonNull(actor, "actor must not be null for POST_CUTOVER audit record");
        return new AuditLogEntry(
                auditId,
                occurredAt,
                actor,
                null,
                targetAccountId,
                targetOrganizationId,
                action,
                changeSummary
        );
    }

    public static AuditLogEntry legacy(
            String auditId,
            Instant occurredAt,
            AccountId legacyRecordedActorAccountId,
            AccountId targetAccountId,
            OrganizationId targetOrganizationId,
            AuditAction action,
            Map<String, Object> changeSummary
    ) {
        Objects.requireNonNull(legacyRecordedActorAccountId, "legacyRecordedActorAccountId must not be null for PRE_CUTOVER audit record");
        return new AuditLogEntry(
                auditId,
                occurredAt,
                null,
                legacyRecordedActorAccountId,
                targetAccountId,
                targetOrganizationId,
                action,
                changeSummary
        );
    }
}
