package identity.domain.model;

/**
 * Domain command enum for organization verification transitions.
 *
 * References: ADR-038 §2.5, D8g.
 */
public enum VerificationCommand {
    VERIFY,
    REJECT,
    REQUEST_INFORMATION
}
