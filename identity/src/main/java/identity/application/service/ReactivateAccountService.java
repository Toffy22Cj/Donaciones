package identity.application.service;

import com.github.f4b6a3.ulid.UlidCreator;
import identity.application.port.out.AccountRepositoryPort;
import identity.application.port.out.AuditLogPort;
import identity.domain.model.Account;
import identity.domain.model.AccountId;
import identity.domain.model.AuditAction;
import identity.domain.model.AuditActor;
import identity.domain.model.AuditLogEntry;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collections;
import java.util.Objects;

@Service
public class ReactivateAccountService {

    private final AccountRepositoryPort accountRepository;
    private final AuditLogPort auditLogPort;
    private final MongoTransactionRetryHelper retryHelper;

    public ReactivateAccountService(AccountRepositoryPort accountRepository, 
                                    AuditLogPort auditLogPort,
                                    MongoTransactionRetryHelper retryHelper) {
        this.accountRepository = accountRepository;
        this.auditLogPort = auditLogPort;
        this.retryHelper = retryHelper;
    }

    public void reactivateAccount(AuditActor actor, AccountId accountId) {
        Objects.requireNonNull(actor, "actor must not be null");

        retryHelper.executeWithRetry(() -> {
            Account account = accountRepository.findById(accountId);
            
            boolean mutated = account.reactivate();
            
            if (mutated) {
                accountRepository.save(account);

                AuditLogEntry auditLog = AuditLogEntry.record(
                        UlidCreator.getUlid().toString(),
                        Instant.now(),
                        actor,
                        account.getAccountId(),
                        null,
                        AuditAction.ACCOUNT_REACTIVATED,
                        Collections.emptyMap()
                );
                auditLogPort.record(auditLog);
            }
        });
    }
}
