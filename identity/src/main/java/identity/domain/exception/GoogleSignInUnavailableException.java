package identity.domain.exception;

/** [Plan] ADR-050 D9 (P-G-9): no se pudieron obtener las llaves de Google (503 `GoogleSignInUnavailable`); el login con contraseña no se toca. */
public class GoogleSignInUnavailableException extends RuntimeException {
    public GoogleSignInUnavailableException(String message) {
        super(message);
    }
}
