package identity.application.service;

import com.traceability.contracts.authentication.AcceptanceRequiredException;
import com.traceability.contracts.authentication.AcceptedLegalVersions;
import com.traceability.contracts.authentication.AuthenticateAccountPort;
import com.traceability.contracts.authentication.AuthenticatedAccount;
import com.traceability.contracts.authentication.AuthenticationFailedException;
import com.traceability.contracts.authentication.EmailNotVerifiedException;
import identity.application.port.out.AccountRepositoryPort;
import identity.application.port.out.PasswordHasherPort;
import identity.domain.exception.InvalidEmailFormatException;
import identity.domain.model.Account;
import identity.domain.model.AccountStatus;
import identity.domain.model.Email;
import identity.domain.model.LegalAcceptance;
import identity.domain.model.LegalVersions;
import identity.domain.model.PasswordHash;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Autenticación por email y contraseña (ADR-038 §2.7; plan B3 §2.2). Sin transacción; solo escribe en un caso (D3,
 * abajo).
 *
 * <p>Los tres fallos (email inexistente o mal formado, contraseña incorrecta, cuenta {@code INACTIVE}) lanzan la misma
 * {@link AuthenticationFailedException} y cuestan lo mismo: {@code matches} se ejecuta siempre, contra un hash ficticio
 * del mismo coste cuando la cuenta no existe, y el estado se comprueba después (ADR-047 Q5).
 *
 * <p>Después, el correo verificado (ADR-050 Enmienda 2, E2-3): si falta, {@link EmailNotVerifiedException}, sin
 * escribir nada.
 *
 * <p>Después, y solo después, las aceptaciones (ADR-050 D3; Enmienda 1, parte 3, E3-2 y E3-3): si la cuenta tiene
 * alguna versión vigente pendiente, el login solo sigue si {@code accepted} trae exactamente esas, y entonces se
 * guardan con una actualización parcial condicionada al hash que se validó y al estado {@code ACTIVE}. Si no las trae,
 * {@link AcceptanceRequiredException} con solo las pendientes. Si la escritura no encuentra el mismo hash (un cambio de
 * contraseña o una desactivación concurrentes), el mismo {@link AuthenticationFailedException}.
 */
@Service
public class AuthenticateAccountPortImpl implements AuthenticateAccountPort {

    private final AccountRepositoryPort accountRepositoryPort;
    private final PasswordHasherPort passwordHasherPort;
    private final PasswordHash dummyHash;
    private final Supplier<LegalVersions> legalVersions;
    private final Clock clock;

    @Autowired
    public AuthenticateAccountPortImpl(AccountRepositoryPort accountRepositoryPort, PasswordHasherPort passwordHasherPort,
                                       ObjectProvider<LegalVersions> legalVersions, ObjectProvider<Clock> clock) {
        this(accountRepositoryPort, passwordHasherPort, legalVersions::getObject, clock.getIfAvailable(Clock::systemUTC));
    }

    AuthenticateAccountPortImpl(AccountRepositoryPort accountRepositoryPort, PasswordHasherPort passwordHasherPort,
                                Supplier<LegalVersions> legalVersions, Clock clock) {
        this.accountRepositoryPort = accountRepositoryPort;
        this.passwordHasherPort = passwordHasherPort;
        this.dummyHash = passwordHasherPort.hash(UUID.randomUUID().toString());
        this.legalVersions = legalVersions;
        this.clock = clock;
    }

    @Override
    public AuthenticatedAccount authenticate(String email, String password, AcceptedLegalVersions accepted) {
        Optional<Account> account = findByEmail(email);

        boolean passwordMatches = passwordHasherPort.matches(password, account.map(Account::getPasswordHash).orElse(dummyHash));

        if (account.isEmpty() || !passwordMatches || account.get().getStatus() == AccountStatus.INACTIVE) {
            throw new AuthenticationFailedException();
        }
        // ADR-050 Enmienda 2, E2-3 (Q1 (a)): después de la contraseña y el estado, antes de las aceptaciones (D3)
        if (!account.get().isEmailVerified()) {
            throw new EmailNotVerifiedException();
        }
        requireCurrentAcceptances(account.get(), accepted == null ? AcceptedLegalVersions.NONE : accepted);
        // ADR-050 Enmienda 1, E1-1: el credentialsNotBefore sale del mismo documento que validó la contraseña
        return new AuthenticatedAccount(account.get().getAccountId().value(), account.get().getCredentialsNotBefore());
    }

    /** E3-2 (pasos 2 a 4) y E3-3. Una cuenta al día no escribe nada. */
    private void requireCurrentAcceptances(Account account, AcceptedLegalVersions accepted) {
        LegalVersions current = legalVersions.get();
        String pendingTerms = isCurrent(account.getTermsAcceptance(), current.terms()) ? null : current.terms();
        String pendingDataPolicy = isCurrent(account.getDataPolicyAcceptance(), current.dataPolicy())
                ? null : current.dataPolicy();
        if (pendingTerms == null && pendingDataPolicy == null) {
            return;
        }
        boolean acceptsThem = (pendingTerms == null || pendingTerms.equals(accepted.termsVersion()))
                && (pendingDataPolicy == null || pendingDataPolicy.equals(accepted.dataPolicyVersion()));
        if (!acceptsThem) {
            throw new AcceptanceRequiredException(pendingTerms, pendingDataPolicy);
        }
        Instant now = clock.instant();
        boolean written = accountRepositoryPort.recordLegalAcceptancesIfUnchanged(account.getAccountId(),
                account.getPasswordHash(),
                pendingTerms == null ? null : new LegalAcceptance(pendingTerms, now),
                pendingDataPolicy == null ? null : new LegalAcceptance(pendingDataPolicy, now));
        if (!written) {
            throw new AuthenticationFailedException();
        }
    }

    private static boolean isCurrent(LegalAcceptance acceptance, String currentVersion) {
        return acceptance != null && currentVersion.equals(acceptance.version());
    }

    private Optional<Account> findByEmail(String email) {
        try {
            return accountRepositoryPort.findByEmail(new Email(email));
        } catch (InvalidEmailFormatException malformed) {
            return Optional.empty();
        }
    }
}
