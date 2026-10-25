package identity.application.service;

import com.github.f4b6a3.ulid.UlidCreator;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import identity.application.authorization.AuthorizationAuditActorMapper;
import identity.application.port.out.AccountRepositoryPort;
import identity.application.port.out.AuditLogPort;
import identity.domain.model.Account;
import identity.domain.model.AccountId;
import identity.domain.model.AuditAction;
import identity.domain.model.AuditLogEntry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Map;

/**
 * Perfil de la propia cuenta (ADR-050 D1; plan R-5): el correo y, opcionales, el nombre y el apellido. Solo la cuenta
 * del principal. Al cambiar, audita {@code PROFILE_UPDATED} con los <b>nombres</b> de los campos que cambiaron, nunca sus
 * valores (P-R5-4). Si nada cambia, no escribe ni audita.
 */
@Service
public class AccountProfileService {

    public record AccountProfile(String email, String givenName, String familyName) {
        @Override
        public String toString() {
            return "AccountProfile[***]";
        }
    }

    private final AccountRepositoryPort accountRepository;
    private final AuditLogPort auditLogPort;
    private final MongoTransactionRetryHelper retryHelper;
    private final AuthorizationAuditActorMapper auditActors;
    private final Clock clock;

    public AccountProfileService(AccountRepositoryPort accountRepository, AuditLogPort auditLogPort,
                                 MongoTransactionRetryHelper retryHelper, AuthorizationAuditActorMapper auditActors,
                                 ObjectProvider<Clock> clock) {
        this.accountRepository = accountRepository;
        this.auditLogPort = auditLogPort;
        this.retryHelper = retryHelper;
        this.auditActors = auditActors;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    public AccountProfile read(AuthorizationPrincipal principal) {
        return profileOf(accountRepository.findById(new AccountId(principal.accountId())));
    }

    /**
     * {@code null} borra el campo.
     *
     * @throws identity.domain.exception.InvalidProfileFieldException si un campo queda vacío tras recortar o pasa de 100
     *         puntos de código; nada cambia
     */
    public AccountProfile update(AuthorizationPrincipal principal, String givenName, String familyName) {
        var actor = auditActors.toAuditActor(principal);
        AccountId accountId = new AccountId(principal.accountId());
        return retryHelper.executeWithRetry(() -> {
            Account account = accountRepository.findById(accountId);
            List<String> changed = account.updateProfile(givenName, familyName);
            if (!changed.isEmpty()) {
                accountRepository.save(account);
                auditLogPort.record(AuditLogEntry.record(UlidCreator.getUlid().toString(), clock.instant(), actor,
                        accountId, null, AuditAction.PROFILE_UPDATED, Map.of("fields", changed)));
            }
            return profileOf(account);
        });
    }

    private static AccountProfile profileOf(Account account) {
        return new AccountProfile(account.getEmail().value(), account.getGivenName(), account.getFamilyName());
    }
}
