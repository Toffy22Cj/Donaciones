package identity.domain.model;

/**
 * Verification status of an Organization in the identity domain.
 *
 * References: ADR-038 §2.5.
 */
public enum VerificationStatus {
    PENDING_VERIFICATION,
    NEEDS_MORE_INFORMATION,
    VERIFIED,
    REJECTED
}
