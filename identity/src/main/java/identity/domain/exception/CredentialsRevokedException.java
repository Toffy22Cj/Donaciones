package identity.domain.exception;

/** ADR-050 D4: el token se emitió antes del {@code credentialsNotBefore} de la cuenta. Solo lo ve el filtro JWT (401). */
public class CredentialsRevokedException extends RuntimeException {
    public CredentialsRevokedException(String message) {
        super(message);
    }
}
