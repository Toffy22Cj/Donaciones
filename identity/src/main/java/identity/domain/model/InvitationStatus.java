package identity.domain.model;

/** Estado guardado de una invitación (ADR-049 D2). {@code EXPIRED} no se guarda: se deriva de {@code expiresAt}. */
public enum InvitationStatus {
    PENDING,
    ACCEPTED,
    REVOKED
}
