package identity.application.service;

import com.github.f4b6a3.ulid.UlidCreator;
import identity.application.port.out.AccountRepositoryPort;
import identity.application.port.out.AuditLogPort;
import identity.application.port.out.PasswordHasherPort;
import identity.domain.model.Account;
import identity.domain.model.AccountId;
import identity.domain.model.AuditAction;
import identity.domain.model.AuditActor;
import identity.domain.model.AuditLogEntry;
import identity.domain.model.PasswordHash;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collections;
import java.util.Objects;

@Service
public class ChangeCredentialsService {

    private final AccountRepositoryPort accountRepository;
    private final PasswordHasherPort passwordHasher;
    private final AuditLogPort auditLogPort;
    private final MongoTransactionRetryHelper retryHelper;

    public ChangeCredentialsService(AccountRepositoryPort accountRepository, 
                                  PasswordHasherPort passwordHasher, 
                                  AuditLogPort auditLogPort,
                                  MongoTransactionRetryHelper retryHelper) {
        this.accountRepository = accountRepository;
        this.passwordHasher = passwordHasher;
        this.auditLogPort = auditLogPort;
        this.retryHelper = retryHelper;
    }

    public void changeCredentials(AuditActor actor, AccountId accountId, String newPlainPassword) {
        Objects.requireNonNull(actor, "actor must not be null");
        // H-P2-1: la misma política que al crear la cuenta
        identity.domain.model.PlainPassword password = new identity.domain.model.PlainPassword(newPlainPassword);

        retryHelper.executeWithRetry(() -> {
            Account account = accountRepository.findById(accountId);
            
            PasswordHash newPasswordHash = passwordHasher.hash(password.value());
            account.changeCredentials(newPasswordHash);
            
            accountRepository.save(account);

            AuditLogEntry auditLog = AuditLogEntry.record(
                    UlidCreator.getUlid().toString(),
                    Instant.now(),
                    actor,
                    account.getAccountId(),
                    null,
                    AuditAction.CREDENTIALS_CHANGED,
                    Collections.emptyMap()
            );
            auditLogPort.record(auditLog);
        });
    }
}
