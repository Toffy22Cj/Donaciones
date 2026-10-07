package identity.domain.exception;

/**
 * Thrown when reading an audit log document from MongoDB that violates audit regime invariants.
 *
 * References: ADR-038 §2.2.
 */
public class CorruptAuditLogEntryException extends RuntimeException {

    private final String auditId;

    public CorruptAuditLogEntryException(String auditId, String message) {
        super("Corrupt audit log entry [" + auditId + "]: " + message);
        this.auditId = auditId;
    }

    public CorruptAuditLogEntryException(String auditId, String message, Throwable cause) {
        super("Corrupt audit log entry [" + auditId + "]: " + message, cause);
        this.auditId = auditId;
    }

    public String getAuditId() {
        return auditId;
    }
}
