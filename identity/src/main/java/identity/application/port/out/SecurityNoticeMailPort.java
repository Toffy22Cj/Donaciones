package identity.application.port.out;

import java.time.Instant;

/**
 * [Plan] Avisos de seguridad por correo, sin enlace con token (ADR-050 Enmienda 2, E2-4). {@link #dispatch} entrega
 * el aviso para enviarlo después de confirmar la operación y vuelve enseguida; nunca lanza. Un aviso nunca lleva
 * contraseñas, tokens, la IP ni el dispositivo (Q5).
 */
public interface SecurityNoticeMailPort {

    void dispatch(SecurityNotice notice);

    /** {@code accountId} y {@code kind} son lo único que puede ir a un log; nunca {@code to}. */
    record SecurityNotice(String accountId, String to, Kind kind, Instant occurredAt) {

        /** [Plan] Los tres casos de E2-4. */
        public enum Kind {
            PASSWORD_CHANGED, PASSWORD_RESET, ACCOUNT_DEACTIVATED,
            /** [Plan] Ampliación de E2-4 (Manuel, #164): vincular y desvincular Google. */
            GOOGLE_LINKED, GOOGLE_UNLINKED
        }

        @Override
        public String toString() {
            return "SecurityNotice[accountId=" + accountId + ", kind=" + kind + "]";
        }
    }
}
