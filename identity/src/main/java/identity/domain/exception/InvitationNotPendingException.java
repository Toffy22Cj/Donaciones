package identity.domain.exception;

/** Revocar una invitación que ya no está pendiente (ADR-049 D6). */
public class InvitationNotPendingException extends RuntimeException {
    public InvitationNotPendingException(String message) {
        super(message);
    }
}
