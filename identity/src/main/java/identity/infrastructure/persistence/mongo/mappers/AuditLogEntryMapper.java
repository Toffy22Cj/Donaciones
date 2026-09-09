package identity.infrastructure.persistence.mongo.mappers;

import identity.domain.model.AccountId;
import identity.domain.model.AuditLogEntry;
import identity.domain.model.OrganizationId;
import identity.infrastructure.persistence.mongo.documents.AuditLogEntryDocument;

public class AuditLogEntryMapper {

    public static AuditLogEntryDocument toDocument(AuditLogEntry entry) {
        if (entry == null) {
            return null;
        }
        AuditLogEntryDocument doc = new AuditLogEntryDocument();
        doc.setAuditId(entry.auditId());
        doc.setOccurredAt(entry.occurredAt());
        doc.setActorAccountId(entry.actorAccountId().value());
        
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

        AccountId targetAccountId = null;
        if (doc.getTargetAccountId() != null) {
            targetAccountId = new AccountId(doc.getTargetAccountId());
        }

        OrganizationId targetOrganizationId = null;
        if (doc.getTargetOrganizationId() != null) {
            targetOrganizationId = new OrganizationId(doc.getTargetOrganizationId());
        }

        return new AuditLogEntry(
            doc.getAuditId(),
            doc.getOccurredAt(),
            new AccountId(doc.getActorAccountId()),
            targetAccountId,
            targetOrganizationId,
            doc.getAction(),
            doc.getChangeSummary()
        );
    }
}
