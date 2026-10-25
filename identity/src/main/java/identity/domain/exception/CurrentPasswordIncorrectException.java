package identity.domain.exception;

/** ADR-050 D5: la contraseña actual no coincide al cambiarla → 400 {@code CurrentPasswordIncorrect}, nunca 401. */
public class CurrentPasswordIncorrectException extends RuntimeException {
    public CurrentPasswordIncorrectException(String message) {
        super(message);
    }
}
