package identity.infrastructure.persistence.mongo.documents;

import identity.domain.model.AuditAction;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.Instant;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

@Document("identity_audit_log")
@CompoundIndexes({
    @CompoundIndex(name = "target_org_occurred_at_idx", def = "{'targetOrganizationId': 1, 'occurredAt': -1}"),
    @CompoundIndex(name = "target_acc_occurred_at_idx", def = "{'targetAccountId': 1, 'occurredAt': -1}")
})
@Getter
@Setter
public class AuditLogEntryDocument {
    @Id
    private String auditId;
    
    private Instant occurredAt;
    private String actorAccountId;
    private String targetAccountId; // nullable
    private String targetOrganizationId; // nullable
    private AuditAction action;
    private Map<String, Object> changeSummary;
}
