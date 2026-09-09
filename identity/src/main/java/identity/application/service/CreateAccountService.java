package identity.application.service;

import com.github.f4b6a3.ulid.UlidCreator;
import identity.application.port.out.AccountRepositoryPort;
import identity.application.port.out.AuditLogPort;
import identity.application.port.out.PasswordHasherPort;
import identity.domain.exception.DuplicateEmailException;
import identity.domain.model.Account;
import identity.domain.model.AuditAction;
import identity.domain.model.AuditLogEntry;
import identity.domain.model.Email;
import identity.domain.model.PasswordHash;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

@Service
public class CreateAccountService {

    private final AccountRepositoryPort accountRepository;
    private final PasswordHasherPort passwordHasher;
    private final AuditLogPort auditLogPort;

    public CreateAccountService(AccountRepositoryPort accountRepository, PasswordHasherPort passwordHasher, AuditLogPort auditLogPort) {
        this.accountRepository = accountRepository;
        this.passwordHasher = passwordHasher;
        this.auditLogPort = auditLogPort;
    }

    @Transactional
    public Account createAccount(Email email, String plainPassword) {
        if (accountRepository.findByEmail(email).isPresent()) {
            throw new DuplicateEmailException("Email is already registered");
        }

        PasswordHash passwordHash = passwordHasher.hash(plainPassword);
        Account newAccount = Account.createAccount(email, passwordHash);

        accountRepository.save(newAccount);

        AuditLogEntry auditLog = new AuditLogEntry(
                UlidCreator.getUlid().toString(),
                Instant.now(),
                newAccount.getAccountId(),
                newAccount.getAccountId(),
                null,
                AuditAction.ACCOUNT_CREATED,
                Map.of("email", email.value())
        );
        auditLogPort.record(auditLog);

        return newAccount;
    }
}
