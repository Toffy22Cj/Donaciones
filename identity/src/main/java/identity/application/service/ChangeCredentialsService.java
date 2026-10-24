package identity.application.service;

import com.github.f4b6a3.ulid.UlidCreator;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import identity.application.authorization.AuthorizationAuditActorMapper;
import identity.application.port.out.AccountRepositoryPort;
import identity.application.port.out.AuditLogPort;
import identity.application.port.out.PasswordHasherPort;
import identity.domain.exception.CurrentPasswordIncorrectException;
import identity.domain.model.Account;
import identity.domain.model.AccountId;
import identity.domain.model.AuditAction;
import identity.domain.model.AuditActor;
import identity.domain.model.AuditLogEntry;
import identity.domain.model.PasswordHash;
import identity.domain.model.PlainPassword;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.Objects;

@Service
public class ChangeCredentialsService {

    private final AccountRepositoryPort accountRepository;
    private final PasswordHasherPort passwordHasher;
    private final AuditLogPort auditLogPort;
    private final MongoTransactionRetryHelper retryHelper;
    private final AuthorizationAuditActorMapper auditActors;
    private final Clock clock;

    public ChangeCredentialsService(AccountRepositoryPort accountRepository, 
                                  PasswordHasherPort passwordHasher, 
                                  AuditLogPort auditLogPort,
                                  MongoTransactionRetryHelper retryHelper,
                                  AuthorizationAuditActorMapper auditActors,
                                  ObjectProvider<Clock> clock) {
        this.accountRepository = accountRepository;
        this.passwordHasher = passwordHasher;
        this.auditLogPort = auditLogPort;
        this.retryHelper = retryHelper;
        this.auditActors = auditActors;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    /**
     * Sin contraseña actual (la reutilizará la recuperación, R-4). También revoca los tokens anteriores (ADR-050 D4;
     * plan R-3 + R-2, P-R3-2): ningún camino cambia la contraseña sin revocar.
     */
    public void changeCredentials(AuditActor actor, AccountId accountId, String newPlainPassword) {
        Objects.requireNonNull(actor, "actor must not be null");
        // H-P2-1: la misma política que al crear la cuenta
        PlainPassword password = new PlainPassword(newPlainPassword);

        retryHelper.executeWithRetry(() -> {
            Account account = accountRepository.findById(accountId);
            applyChange(actor, account, password);
        });
    }

    /**
     * ADR-050 D5, en una transacción y en este orden (plan R-3 + R-2, P-R3-1 (a)): 1. la contraseña actual con BCrypt;
     * 2. la política de la nueva; 3. guardar el hash, fijar {@code credentialsNotBefore} y auditar sin contraseñas.
     *
     * La cuenta es la del principal autenticado, que también es el actor de la auditoría.
     *
     * @return el {@code credentialsNotBefore} confirmado, que es el {@code iat} del token nuevo
     * @throws CurrentPasswordIncorrectException si la actual no coincide; nada cambia
     */
    public Instant changeOwnCredentials(AuthorizationPrincipal principal, String currentPlainPassword, String newPlainPassword) {
        AuditActor self = auditActors.toAuditActor(principal);
        AccountId accountId = new AccountId(principal.accountId());

        return retryHelper.executeWithRetry(() -> {
            Account account = accountRepository.findById(accountId);
            if (currentPlainPassword == null || !passwordHasher.matches(currentPlainPassword, account.getPasswordHash())) {
                throw new CurrentPasswordIncorrectException("Current password does not match");
            }
            PlainPassword password = new PlainPassword(newPlainPassword);
            applyChange(self, account, password);
            return account.getCredentialsNotBefore();
        });
    }

    private void applyChange(AuditActor actor, Account account, PlainPassword password) {
        Instant now = clock.instant();
        PasswordHash newPasswordHash = passwordHasher.hash(password.value());
        account.changeCredentials(newPasswordHash, now);

        accountRepository.save(account);

        AuditLogEntry auditLog = AuditLogEntry.record(
                UlidCreator.getUlid().toString(),
                now,
                actor,
                account.getAccountId(),
                null,
                AuditAction.CREDENTIALS_CHANGED,
                Collections.emptyMap()
        );
        auditLogPort.record(auditLog);
    }
}
