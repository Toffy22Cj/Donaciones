package identity.domain.model;

/**
 * [Plan] Estados de un token de verificación del correo (ADR-050 Enmienda 2, E2-2). {@code EXPIRED} no se guarda: se
 * deriva de {@code expiresAt}, como en la recuperación (D8).
 */
public enum EmailVerificationTokenStatus {
    PENDING,
    USED,
    SUPERSEDED
}
