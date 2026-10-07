package identity.application.service;

import com.github.f4b6a3.ulid.UlidCreator;
import identity.application.port.out.AccountRepositoryPort;
import identity.application.port.out.AuditLogPort;
import identity.application.port.out.PasswordHasherPort;
import identity.domain.exception.DuplicateEmailException;
import identity.domain.model.Account;
import identity.domain.model.AuditAction;
import identity.domain.model.AuditActor;
import identity.domain.model.AuditLogEntry;
import identity.domain.model.Email;
import identity.domain.model.PlainPassword;
import identity.domain.model.PasswordHash;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

@Service
public class CreateAccountService {

    private final AccountRepositoryPort accountRepository;
    private final PasswordHasherPort passwordHasher;
    private final AuditLogPort auditLogPort;
    private final MongoTransactionRetryHelper retryHelper;

    public CreateAccountService(AccountRepositoryPort accountRepository, 
                                PasswordHasherPort passwordHasher, 
                                AuditLogPort auditLogPort,
                                MongoTransactionRetryHelper retryHelper) {
        this.accountRepository = accountRepository;
        this.passwordHasher = passwordHasher;
        this.auditLogPort = auditLogPort;
        this.retryHelper = retryHelper;
    }

    public Account createAccount(Email email, String plainPassword) {
        // H-P2-1: la política se comprueba antes de leer nada
        PlainPassword password = new PlainPassword(plainPassword);
        return retryHelper.executeWithRetry(() -> {
            if (accountRepository.findByEmail(email).isPresent()) {
                throw new DuplicateEmailException("Email is already registered");
            }

            PasswordHash passwordHash = passwordHasher.hash(password.value());
            Account newAccount = Account.createAccount(email, passwordHash);

            accountRepository.save(newAccount);

            AuditActor actor = new AuditActor.AccountAuditActor(newAccount.getAccountId());
            AuditLogEntry auditLog = AuditLogEntry.record(
                    UlidCreator.getUlid().toString(),
                    Instant.now(),
                    actor,
                    newAccount.getAccountId(),
                    null,
                    AuditAction.ACCOUNT_CREATED,
                    Map.of("email", email.value(), "selfRegistration", true)
            );
            auditLogPort.record(auditLog);

            return newAccount;
        });
    }
}
