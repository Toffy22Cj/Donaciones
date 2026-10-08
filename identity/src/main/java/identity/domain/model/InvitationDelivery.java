package identity.domain.model;

/** Resultado del envío del correo de una invitación (ADR-049 D5, DD-64). */
public enum InvitationDelivery {
    PENDING,
    SENT,
    FAILED
}
