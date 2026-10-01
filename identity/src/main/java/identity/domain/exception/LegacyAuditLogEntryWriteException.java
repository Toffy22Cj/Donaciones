package identity.domain.exception;

/**
 * Thrown when an attempt is made to persist a legacy PRE_CUTOVER audit log entry.
 *
 * References: ADR-038 §2.2.
 */
public class LegacyAuditLogEntryWriteException extends RuntimeException {

    public LegacyAuditLogEntryWriteException(String message) {
        super(message);
    }
}
