package identity.domain.exception;

/** Token desconocido, caducado, revocado o usado, o de otro email: siempre la misma respuesta (ADR-049 D4, DD-63). Nunca lleva el token. */
public class InvitationNotAcceptableException extends RuntimeException {
    public InvitationNotAcceptableException(String message) {
        super(message);
    }
}
