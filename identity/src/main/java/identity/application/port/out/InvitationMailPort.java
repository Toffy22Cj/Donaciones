package identity.application.port.out;

import java.time.Instant;

/**
 * Envío del correo de invitación (ADR-049 D1, D5). Lo implementa {@code app} por SMTP; {@code identity} no depende de
 * Spring Mail. El correo lleva solo el nombre de la organización, el rol, el enlace con el token en el fragmento y la
 * caducidad: ningún dato de quien invita.
 */
public interface InvitationMailPort {

    /** Lanza una excepción si el envío falla; quien llama la registra como {@code delivery = FAILED}. */
    void send(InvitationMail mail);

    /** {@code toString()} nunca muestra el token ni el email. */
    record InvitationMail(String invitationId, String to, String organizationName, String role, String token,
                          Instant expiresAt) {
        @Override
        public String toString() {
            return "InvitationMail[" + invitationId + ", " + role + "]";
        }
    }
}
