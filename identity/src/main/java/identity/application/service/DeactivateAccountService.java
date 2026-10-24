package identity.application.service;

import com.github.f4b6a3.ulid.UlidCreator;
import identity.application.port.out.AccountRepositoryPort;
import identity.application.port.out.AuditLogPort;
import identity.domain.model.Account;
import identity.domain.model.AccountId;
import identity.domain.model.AuditAction;
import identity.domain.model.AuditActor;
import identity.domain.model.AuditLogEntry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.Objects;

@Service
public class DeactivateAccountService {

    private final AccountRepositoryPort accountRepository;
    private final AuditLogPort auditLogPort;
    private final MongoTransactionRetryHelper retryHelper;
    private final Clock clock;

    public DeactivateAccountService(AccountRepositoryPort accountRepository, 
                                    AuditLogPort auditLogPort,
                                    MongoTransactionRetryHelper retryHelper,
                                    ObjectProvider<Clock> clock) {
        this.accountRepository = accountRepository;
        this.auditLogPort = auditLogPort;
        this.retryHelper = retryHelper;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    public void deactivateAccount(AuditActor actor, AccountId accountId) {
        Objects.requireNonNull(actor, "actor must not be null");

        retryHelper.executeWithRetry(() -> {
            Account account = accountRepository.findById(accountId);
            Instant now = clock.instant();
            
            // ADR-050 D4 y D6: desactivar revoca los tokens anteriores
            boolean mutated = account.deactivate(now);
            
            if (mutated) {
                accountRepository.save(account);

                AuditLogEntry auditLog = AuditLogEntry.record(
                        UlidCreator.getUlid().toString(),
                        now,
                        actor,
                        account.getAccountId(),
                        null,
                        AuditAction.ACCOUNT_DEACTIVATED,
                        Collections.emptyMap()
                );
                auditLogPort.record(auditLog);
            }
        });
    }
}
