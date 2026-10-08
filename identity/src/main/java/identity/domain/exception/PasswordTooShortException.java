package identity.domain.exception;

/** H-P2-1 (Carlos, 2026-10-07): la contraseña tiene menos de 12 caracteres. El mensaje nunca incluye la contraseña. */
public class PasswordTooShortException extends RuntimeException {
    public PasswordTooShortException(String message) {
        super(message);
    }
}
