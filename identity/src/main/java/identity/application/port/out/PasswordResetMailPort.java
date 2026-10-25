package identity.application.port.out;

import java.time.Instant;

/**
 * Correo de recuperación (ADR-050 D8, Q5 (a)). {@link #dispatch} <b>entrega</b> el correo para enviarlo después y vuelve
 * enseguida; nunca lanza por un fallo de SMTP ni por una cola llena.
 */
public interface PasswordResetMailPort {

    void dispatch(PasswordResetMail mail);

    /** {@code tokenId} es lo único que puede ir a un log. */
    record PasswordResetMail(String tokenId, String to, String token, Instant expiresAt) {
        @Override
        public String toString() {
            return "PasswordResetMail[tokenId=" + tokenId + "]";
        }
    }
}
