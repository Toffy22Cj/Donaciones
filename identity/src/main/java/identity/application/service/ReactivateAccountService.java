package identity.application.service;

import com.github.f4b6a3.ulid.UlidCreator;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import identity.application.authorization.AuthorizationAuditActorMapper;
import identity.application.authorization.PlatformAuthorizationPolicy;
import identity.application.authorization.PlatformCommandType;
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
    private final PlatformAuthorizationPolicy policy;
    private final AuthorizationAuditActorMapper auditActorMapper;

    public ReactivateAccountService(AccountRepositoryPort accountRepository, 
                                    AuditLogPort auditLogPort,
                                    MongoTransactionRetryHelper retryHelper,
                                    PlatformAuthorizationPolicy policy,
                                    AuthorizationAuditActorMapper auditActorMapper) {
        this.accountRepository = accountRepository;
        this.auditLogPort = auditLogPort;
        this.retryHelper = retryHelper;
        this.policy = policy;
        this.auditActorMapper = auditActorMapper;
    }

    /**
     * ADR-050 D7 (plan de cierre, §D7; P-D7-1): solo el administrador de plataforma reactiva. Autoriza antes de leer
     * la cuenta y audita con su cuenta como actor. Una cuenta ya activa no cambia ni se audita (P-D7-3). No toca
     * {@code credentialsNotBefore}, así que los tokens anteriores a la desactivación siguen rechazados (D4).
     */
    public void reactivateAccount(AuthorizationPrincipal principal, AccountId accountId) {
        Objects.requireNonNull(principal, "principal must not be null");
        policy.authorize(principal, PlatformCommandType.REACTIVATE_ACCOUNT);
        reactivateAccount(auditActorMapper.toAuditActor(principal), accountId);
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
