package identity.application.port.out;

import java.time.Instant;

/**
 * [Plan] Correo de verificación (ADR-050 Enmienda 2, E2-2). {@link #dispatch} <b>entrega</b> el correo para enviarlo
 * después y vuelve enseguida; nunca lanza por un fallo de SMTP ni por una cola llena.
 */
public interface EmailVerificationMailPort {

    void dispatch(EmailVerificationMail mail);

    /** {@code tokenId} es lo único que puede ir a un log. */
    record EmailVerificationMail(String tokenId, String to, String token, Instant expiresAt) {
        @Override
        public String toString() {
            return "EmailVerificationMail[tokenId=" + tokenId + "]";
        }
    }
}
