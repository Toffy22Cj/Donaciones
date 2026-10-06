package identity.application.service;

import com.github.f4b6a3.ulid.UlidCreator;
import identity.application.port.out.AccountRepositoryPort;
import identity.application.port.out.AuditLogPort;
import identity.application.port.out.PlatformAuthorityStatePort;
import identity.domain.exception.BootstrapTargetAccountNotFoundException;
import identity.domain.exception.PlatformAlreadyBootstrappedException;
import identity.domain.exception.PlatformAuthorityInconsistentStateException;
import identity.domain.exception.PlatformAuthorityInvariantViolationException;
import identity.domain.model.Account;
import identity.domain.model.AccountId;
import identity.domain.model.AuditAction;
import identity.domain.model.AuditActor;
import identity.domain.model.AuditLogEntry;
import identity.domain.model.Email;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

/**
 * Application service for bootstrapping the initial platform administrator (ADR-038 §2.4).
 */
@Service
public class BootstrapPlatformAuthorityService {

    public static final String PROCESS_ID = "platform-bootstrap";

    private final AccountRepositoryPort accountRepository;
    private final PlatformAuthorityStatePort platformAuthorityStatePort;
    private final AuditLogPort auditLogPort;
    private final MongoTransactionRetryHelper retryHelper;

    public BootstrapPlatformAuthorityService(
            AccountRepositoryPort accountRepository,
            PlatformAuthorityStatePort platformAuthorityStatePort,
            AuditLogPort auditLogPort,
            MongoTransactionRetryHelper retryHelper
    ) {
        this.accountRepository = accountRepository;
        this.platformAuthorityStatePort = platformAuthorityStatePort;
        this.auditLogPort = auditLogPort;
        this.retryHelper = retryHelper;
    }

    public AccountId bootstrap(String targetEmail) {
        Email email = new Email(targetEmail);
        AuditActor actor = new AuditActor.SystemAuditActor(PROCESS_ID);

        return retryHelper.executeWithRetry(() -> {
            // 1. si platformAuthorityStatePort.exists() -> PlatformAlreadyBootstrappedException
            if (platformAuthorityStatePort.exists()) {
                throw new PlatformAlreadyBootstrappedException("Platform authority state already exists");
            }

            // 2. si accountRepository.existsAnyWithPlatformAuthority() -> PlatformAuthorityInconsistentStateException
            if (accountRepository.existsAnyWithPlatformAuthority()) {
                throw new PlatformAuthorityInconsistentStateException("An account with platform authority already exists but singleton state is missing");
            }

            // 3. si accountRepository.findByEmail(email) está vacío -> BootstrapTargetAccountNotFoundException
            Account account = accountRepository.findByEmail(email)
                    .orElseThrow(BootstrapTargetAccountNotFoundException::new);

            // 4. account.grantPlatformAuthority(): una cuenta inactiva lanza la excepción ya existente
            account.grantPlatformAuthority();

            // 5. platformAuthorityStatePort.initialize() — primera escritura (colisión de bootstraps concurrentes)
            platformAuthorityStatePort.initialize();

            // 6. si accountRepository.grantPlatformAuthorityIfAbsent(id) es false -> PlatformAuthorityInvariantViolationException
            if (!accountRepository.grantPlatformAuthorityIfAbsent(account.getAccountId())) {
                throw new PlatformAuthorityInvariantViolationException(
                        "Failed conditional write for grant platform authority on account: " + account.getAccountId().value()
                );
            }

            // 7. auditoría: BOOTSTRAP_PLATFORM_AUTHORITY, actor de sistema, cuenta destino (sin email, solo AccountId)
            AuditLogEntry auditLog = AuditLogEntry.record(
                    UlidCreator.getUlid().toString(),
                    Instant.now(),
                    actor,
                    account.getAccountId(),
                    null,
                    AuditAction.BOOTSTRAP_PLATFORM_AUTHORITY,
                    Map.of()
            );
            auditLogPort.record(auditLog);

            return account.getAccountId();
        });
    }
}
