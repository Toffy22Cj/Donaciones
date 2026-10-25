package identity.domain.exception;

/**
 * ADR-050 D8: token de recuperación desconocido, caducado, usado, reemplazado o de una cuenta {@code INACTIVE}. Los cinco
 * son la misma excepción y el mismo 400 {@code PasswordResetTokenInvalid}; el mensaje nunca lleva el token.
 */
public class PasswordResetTokenInvalidException extends RuntimeException {
    public PasswordResetTokenInvalidException() {
        super("Password reset token is not valid");
    }
}
