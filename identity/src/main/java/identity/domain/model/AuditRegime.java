package identity.domain.model;

/**
 * Historical regime of an audit log entry.
 *
 * References: ADR-038 §2.2.
 */
public enum AuditRegime {
    PRE_CUTOVER,
    POST_CUTOVER
}
