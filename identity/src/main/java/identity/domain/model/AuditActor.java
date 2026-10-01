package identity.domain.model;

import java.util.Objects;

/**
 * Sealed interface representing the actor who executed an auditable identity action.
 *
 * References: ADR-038 §2.2.
 */
public sealed interface AuditActor permits AuditActor.AccountAuditActor, AuditActor.SystemAuditActor {

    record AccountAuditActor(AccountId accountId) implements AuditActor {
        public AccountAuditActor {
            Objects.requireNonNull(accountId, "accountId must not be null");
        }
    }

    record SystemAuditActor(String processId) implements AuditActor {
        public SystemAuditActor {
            if (processId == null || processId.isBlank()) {
                throw new IllegalArgumentException("processId must not be null or blank");
            }
        }
    }
}
