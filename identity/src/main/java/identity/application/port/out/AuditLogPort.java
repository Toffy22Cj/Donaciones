package identity.application.port.out;

import identity.domain.model.AuditLogEntry;

public interface AuditLogPort {
    /**
     * Records a business event to the immutable audit log.
     * @param entry the audit log entry containing the event details
     */
    void record(AuditLogEntry entry);
}
