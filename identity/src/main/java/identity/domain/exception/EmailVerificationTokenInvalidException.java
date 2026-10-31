package identity.domain.exception;

/**
 * [Plan] ADR-050 Enmienda 2, E2-2: token de verificación desconocido, caducado, usado, sustituido o de una cuenta
 * {@code INACTIVE}. Todos son la misma excepción y el mismo 400 {@code EmailVerificationTokenInvalid}; el mensaje
 * nunca lleva el token.
 */
public class EmailVerificationTokenInvalidException extends RuntimeException {
    public EmailVerificationTokenInvalidException() {
        super("Email verification token is not valid");
    }
}
