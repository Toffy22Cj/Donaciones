package identity.application.service;

import com.github.f4b6a3.ulid.UlidCreator;
import identity.application.port.out.AccountRepositoryPort;
import identity.application.port.out.AuditLogPort;
import identity.application.port.out.PasswordHasherPort;
import identity.domain.model.Account;
import identity.domain.model.AccountId;
import identity.domain.model.AuditAction;
import identity.domain.model.AuditLogEntry;
import identity.domain.model.PasswordHash;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

@Service
public class ChangeCredentialsService {

    private final AccountRepositoryPort accountRepository;
    private final PasswordHasherPort passwordHasher;
    private final AuditLogPort auditLogPort;

    public ChangeCredentialsService(AccountRepositoryPort accountRepository, PasswordHasherPort passwordHasher, AuditLogPort auditLogPort) {
        this.accountRepository = accountRepository;
        this.passwordHasher = passwordHasher;
        this.auditLogPort = auditLogPort;
    }

    @Transactional
    public void changeCredentials(AccountId accountId, String newPlainPassword) {
        Account account = accountRepository.findById(accountId);
        
        PasswordHash newPasswordHash = passwordHasher.hash(newPlainPassword);
        account.changeCredentials(newPasswordHash);
        
        accountRepository.save(account);

        AuditLogEntry auditLog = new AuditLogEntry(
                UlidCreator.getUlid().toString(),
                Instant.now(),
                account.getAccountId(),
                account.getAccountId(),
                null,
                AuditAction.CREDENTIALS_CHANGED,
                Map.of()
        );
        auditLogPort.record(auditLog);
    }
}
