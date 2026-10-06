package identity.application.service;

import com.github.f4b6a3.ulid.UlidCreator;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import identity.application.authorization.AuthorizationAuditActorMapper;
import identity.application.authorization.PlatformAuthorizationPolicy;
import identity.application.authorization.PlatformCommandType;
import identity.application.port.out.AccountRepositoryPort;
import identity.application.port.out.AuditLogPort;
import identity.application.port.out.PlatformAuthorityStatePort;
import identity.domain.exception.AccountNotFoundException;
import identity.domain.exception.InsufficientPlatformAuthorityException;
import identity.domain.exception.LastPlatformAdministratorException;
import identity.domain.exception.PlatformAuthorityInvariantViolationException;
import identity.domain.exception.PlatformAuthorityStateMissingException;
import identity.domain.model.Account;
import identity.domain.model.AccountId;
import identity.domain.model.AccountStatus;
import identity.domain.model.AuditAction;
import identity.domain.model.AuditActor;
import identity.domain.model.AuditLogEntry;
import identity.domain.model.PlatformAuthority;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Application service for revoking platform authority from an account (ADR-038 §2.3).
 */
@Service
public class RevokePlatformAuthorityService {

    private final AccountRepositoryPort accountRepository;
    private final PlatformAuthorityStatePort platformAuthorityStatePort;
    private final AuditLogPort auditLogPort;
    private final MongoTransactionRetryHelper retryHelper;
    private final PlatformAuthorizationPolicy policy;
    private final AuthorizationAuditActorMapper auditActorMapper;

    public RevokePlatformAuthorityService(AccountRepositoryPort accountRepository,
                                          PlatformAuthorityStatePort platformAuthorityStatePort,
                                          AuditLogPort auditLogPort,
                                          MongoTransactionRetryHelper retryHelper,
                                          PlatformAuthorizationPolicy policy,
                                          AuthorizationAuditActorMapper auditActorMapper) {
        this.accountRepository = accountRepository;
        this.platformAuthorityStatePort = platformAuthorityStatePort;
        this.auditLogPort = auditLogPort;
        this.retryHelper = retryHelper;
        this.policy = policy;
        this.auditActorMapper = auditActorMapper;
    }

    public void revokePlatformAuthority(AuthorizationPrincipal principal, AccountId targetAccountId) {
        Objects.requireNonNull(principal, "principal must not be null");
        Objects.requireNonNull(targetAccountId, "targetAccountId must not be null");

        policy.authorize(principal, PlatformCommandType.REVOKE_PLATFORM_AUTHORITY);
        AuditActor actor = auditActorMapper.toAuditActor(principal);

        retryHelper.executeWithRetry(() -> {
            AccountId callerAccountId = new AccountId(principal.accountId());
            Account callerAccount;
            try {
                callerAccount = accountRepository.findById(callerAccountId);
            } catch (AccountNotFoundException e) {
                throw new InsufficientPlatformAuthorityException("Caller account not found");
            }
            if (callerAccount.getStatus() != AccountStatus.ACTIVE || callerAccount.getPlatformAuthority() != PlatformAuthority.ADMINISTRATOR) {
                throw new InsufficientPlatformAuthorityException("Caller account is not active or lacks platform authority");
            }

            if (!platformAuthorityStatePort.exists()) {
                throw new PlatformAuthorityStateMissingException("Platform authority state document is missing");
            }

            Account targetAccount = accountRepository.findById(targetAccountId);
            targetAccount.revokePlatformAuthority();

            if (!platformAuthorityStatePort.decrementAdministratorsIfMoreThanOne()) {
                throw new LastPlatformAdministratorException("Cannot revoke last platform administrator");
            }

            if (!accountRepository.revokePlatformAuthorityIfHeld(targetAccountId)) {
                throw new PlatformAuthorityInvariantViolationException(
                        "Failed conditional write for revoke platform authority on account: " + targetAccountId.value()
                );
            }

            AuditLogEntry auditLog = AuditLogEntry.record(
                    UlidCreator.getUlid().toString(),
                    Instant.now(),
                    actor,
                    targetAccountId,
                    null,
                    AuditAction.PLATFORM_AUTHORITY_REVOKED,
                    Map.of()
            );
            auditLogPort.record(auditLog);
        });
    }
}
