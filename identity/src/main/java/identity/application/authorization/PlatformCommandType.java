package identity.application.authorization;

/**
 * Commands subject to platform-level authorization policies (ADR-038 §2.7).
 */
public enum PlatformCommandType {
    VERIFY_ORGANIZATION,
    REJECT_ORGANIZATION,
    REQUEST_ORGANIZATION_INFORMATION,
    GRANT_PLATFORM_AUTHORITY,
    REVOKE_PLATFORM_AUTHORITY,
    /** Cola de verificación: organizaciones pendientes (autorización (3) de Carlos, §3.1). */
    READ_VERIFICATION_QUEUE
}
