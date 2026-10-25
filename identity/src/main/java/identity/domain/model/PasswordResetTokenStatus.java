package identity.domain.model;

/** Estados de un token de recuperación (ADR-050 D8). {@code EXPIRED} no se guarda: se deriva de {@code expiresAt}. */
public enum PasswordResetTokenStatus {
    PENDING,
    USED,
    SUPERSEDED
}
