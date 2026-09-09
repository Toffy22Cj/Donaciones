package identity.application.service;

import com.github.f4b6a3.ulid.UlidCreator;
import identity.application.port.out.AccountRepositoryPort;
import identity.application.port.out.AuditLogPort;
import identity.domain.model.Account;
import identity.domain.model.AccountId;
import identity.domain.model.AuditAction;
import identity.domain.model.AuditLogEntry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

@Service
public class ReactivateAccountService {

    private final AccountRepositoryPort accountRepository;
    private final AuditLogPort auditLogPort;

    public ReactivateAccountService(AccountRepositoryPort accountRepository, AuditLogPort auditLogPort) {
        this.accountRepository = accountRepository;
        this.auditLogPort = auditLogPort;
    }

    @Transactional
    public void reactivateAccount(AccountId accountId) {
        Account account = accountRepository.findById(accountId);
        
        boolean mutated = account.reactivate();
        
        if (mutated) {
            accountRepository.save(account);

            AuditLogEntry auditLog = new AuditLogEntry(
                    UlidCreator.getUlid().toString(),
                    Instant.now(),
                    account.getAccountId(),
                    account.getAccountId(),
                    null,
                    AuditAction.ACCOUNT_REACTIVATED,
                    Map.of()
            );
            auditLogPort.record(auditLog);
        }
    }
}
