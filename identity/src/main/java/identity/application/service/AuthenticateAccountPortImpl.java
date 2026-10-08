package identity.application.service;

import com.traceability.contracts.authentication.AuthenticateAccountPort;
import com.traceability.contracts.authentication.AuthenticationFailedException;
import identity.application.port.out.AccountRepositoryPort;
import identity.application.port.out.PasswordHasherPort;
import identity.domain.exception.InvalidEmailFormatException;
import identity.domain.model.Account;
import identity.domain.model.AccountStatus;
import identity.domain.model.Email;
import identity.domain.model.PasswordHash;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Autenticación por email y contraseña (ADR-038 §2.7; plan B3 §2.2). Solo lectura, sin transacción.
 *
 * <p>Los tres fallos (email inexistente o mal formado, contraseña incorrecta, cuenta {@code INACTIVE}) lanzan la misma
 * {@link AuthenticationFailedException} y cuestan lo mismo: {@code matches} se ejecuta siempre, contra un hash ficticio
 * del mismo coste cuando la cuenta no existe, y el estado se comprueba después (ADR-047 Q5).
 */
@Service
public class AuthenticateAccountPortImpl implements AuthenticateAccountPort {

    private final AccountRepositoryPort accountRepositoryPort;
    private final PasswordHasherPort passwordHasherPort;
    private final PasswordHash dummyHash;

    public AuthenticateAccountPortImpl(AccountRepositoryPort accountRepositoryPort, PasswordHasherPort passwordHasherPort) {
        this.accountRepositoryPort = accountRepositoryPort;
        this.passwordHasherPort = passwordHasherPort;
        this.dummyHash = passwordHasherPort.hash(UUID.randomUUID().toString());
    }

    @Override
    public String authenticate(String email, String password) {
        Optional<Account> account = findByEmail(email);

        boolean passwordMatches = passwordHasherPort.matches(password, account.map(Account::getPasswordHash).orElse(dummyHash));

        if (account.isEmpty() || !passwordMatches || account.get().getStatus() == AccountStatus.INACTIVE) {
            throw new AuthenticationFailedException();
        }
        return account.get().getAccountId().value();
    }

    private Optional<Account> findByEmail(String email) {
        try {
            return accountRepositoryPort.findByEmail(new Email(email));
        } catch (InvalidEmailFormatException malformed) {
            return Optional.empty();
        }
    }
}
