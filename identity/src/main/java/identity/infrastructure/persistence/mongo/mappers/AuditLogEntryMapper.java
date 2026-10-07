package identity.infrastructure.persistence.mongo.mappers;

import identity.domain.exception.CorruptAuditLogEntryException;
import identity.domain.exception.LegacyAuditLogEntryWriteException;
import identity.domain.model.AccountId;
import identity.domain.model.AuditActor;
import identity.domain.model.AuditLogEntry;
import identity.domain.model.OrganizationId;
import identity.infrastructure.persistence.mongo.documents.ActorDocument;
import identity.infrastructure.persistence.mongo.documents.AuditLogEntryDocument;

public class AuditLogEntryMapper {

    public static AuditLogEntryDocument toDocument(AuditLogEntry entry) {
        if (entry == null) {
            return null;
        }
        AuditLogEntryDocument doc = new AuditLogEntryDocument();
        doc.setAuditId(entry.auditId());
        doc.setOccurredAt(entry.occurredAt());

        // Java 21 pattern matching switch over sealed AuditActor
        switch (entry.actor()) {
            case AuditActor.AccountAuditActor acc ->
                doc.setActor(new ActorDocument("ACCOUNT", acc.accountId().value(), null));
            case AuditActor.SystemAuditActor sys ->
                doc.setActor(new ActorDocument("SYSTEM", null, sys.processId()));
            case null ->
                throw new LegacyAuditLogEntryWriteException(
                        "Cannot persist legacy PRE_CUTOVER audit log entry with auditId [" + entry.auditId() + "]");
        }

        if (entry.targetAccountId() != null) {
            doc.setTargetAccountId(entry.targetAccountId().value());
        }
        if (entry.targetOrganizationId() != null) {
            doc.setTargetOrganizationId(entry.targetOrganizationId().value());
        }
        doc.setAction(entry.action());
        doc.setChangeSummary(entry.changeSummary());
        return doc;
    }

    public static AuditLogEntry toDomain(AuditLogEntryDocument doc) {
        if (doc == null) {
            return null;
        }
        String auditId = doc.getAuditId();

        try {
            AccountId targetAccountId = doc.getTargetAccountId() != null ? new AccountId(doc.getTargetAccountId()) : null;
            OrganizationId targetOrganizationId = doc.getTargetOrganizationId() != null ? new OrganizationId(doc.getTargetOrganizationId()) : null;

            ActorDocument actorDoc = doc.getActor();
            String actorAccountId = doc.getActorAccountId();

            // 1. actor ausente
            if (actorDoc == null) {
                if (actorAccountId != null) {
                    // PRE_CUTOVER válido
                    return AuditLogEntry.legacy(
                            auditId,
                            doc.getOccurredAt(),
                            new AccountId(actorAccountId),
                            targetAccountId,
                            targetOrganizationId,
                            doc.getAction(),
                            doc.getChangeSummary()
                    );
                } else {
                    // ausente + ausente -> CORRUPT
                    throw new CorruptAuditLogEntryException(auditId, "Neither actor nor actorAccountId present in document");
                }
            }

            // 2. actor presente
            if (actorAccountId != null) {
                // presente + presente -> CORRUPT
                throw new CorruptAuditLogEntryException(auditId, "Both actor and actorAccountId present in document");
            }

            // 3. Validar contenido de actorDoc
            String type = actorDoc.getType();
            if (type == null) {
                throw new CorruptAuditLogEntryException(auditId, "Actor subdocument has null type");
            }

            if ("ACCOUNT".equals(type)) {
                if (actorDoc.getAccountId() == null || actorDoc.getAccountId().isBlank()) {
                    throw new CorruptAuditLogEntryException(auditId, "ACCOUNT actor has null or blank accountId");
                }
                if (actorDoc.getProcessId() != null) {
                    throw new CorruptAuditLogEntryException(auditId, "ACCOUNT actor has non-null processId");
                }
                AuditActor.AccountAuditActor actor = new AuditActor.AccountAuditActor(new AccountId(actorDoc.getAccountId()));
                return AuditLogEntry.record(
                        auditId,
                        doc.getOccurredAt(),
                        actor,
                        targetAccountId,
                        targetOrganizationId,
                        doc.getAction(),
                        doc.getChangeSummary()
                );
            } else if ("SYSTEM".equals(type)) {
                if (actorDoc.getProcessId() == null || actorDoc.getProcessId().isBlank()) {
                    throw new CorruptAuditLogEntryException(auditId, "SYSTEM actor has null or blank processId");
                }
                if (actorDoc.getAccountId() != null) {
                    throw new CorruptAuditLogEntryException(auditId, "SYSTEM actor has non-null accountId");
                }
                AuditActor.SystemAuditActor actor = new AuditActor.SystemAuditActor(actorDoc.getProcessId());
                return AuditLogEntry.record(
                        auditId,
                        doc.getOccurredAt(),
                        actor,
                        targetAccountId,
                        targetOrganizationId,
                        doc.getAction(),
                        doc.getChangeSummary()
                );
            } else {
                throw new CorruptAuditLogEntryException(auditId, "Unknown actor type: " + type);
            }
        } catch (CorruptAuditLogEntryException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new CorruptAuditLogEntryException(auditId, "Failed to map document to domain due to invalid value: " + e.getMessage(), e);
        }
    }
}
