package identity.application.service;

import identity.application.port.out.AccountRepositoryPort;
import identity.domain.model.AccountId;
import org.springframework.stereotype.Service;

/**
 * Correo de la propia cuenta (ficha L-FRONTS, A3; M-26): solo el {@code accountId} del actor autenticado, nunca uno
 * recibido en la ruta. Ya normalizado por {@link identity.domain.model.Email} (DD-74). Lectura sin transacción y sin
 * reglas nuevas; no registra el correo. La ficha N1 ({@code GET /me}) no cambia.
 */
@Service
public class AccountEmailQuery {

    private final AccountRepositoryPort accounts;

    public AccountEmailQuery(AccountRepositoryPort accounts) {
        this.accounts = accounts;
    }

    public String emailOf(String accountId) {
        return accounts.findById(new AccountId(accountId)).getEmail().value();
    }

    /** [Plan] ADR-050 Enmienda 2, E2-1: el correo y si está verificado, para {@code GET /account}. */
    public AccountEmail account(String accountId) {
        identity.domain.model.Account account = accounts.findById(new AccountId(accountId));
        return new AccountEmail(account.getEmail().value(), account.isEmailVerified());
    }

    /** [Plan] */
    public record AccountEmail(String email, boolean emailVerified) {}
}
