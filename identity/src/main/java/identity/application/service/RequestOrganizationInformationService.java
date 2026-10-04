package identity.application.service;

import com.github.f4b6a3.ulid.UlidCreator;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import identity.application.authorization.AuthorizationAuditActorMapper;
import identity.application.authorization.PlatformAuthorizationPolicy;
import identity.application.authorization.PlatformCommandType;
import identity.application.port.out.AccountRepositoryPort;
import identity.application.port.out.AuditLogPort;
import identity.application.port.out.OrganizationRepositoryPort;
import identity.domain.exception.AccountNotFoundException;
import identity.domain.exception.InsufficientPlatformAuthorityException;
import identity.domain.model.Account;
import identity.domain.model.AccountId;
import identity.domain.model.AccountStatus;
import identity.domain.model.AuditAction;
import identity.domain.model.AuditActor;
import identity.domain.model.AuditLogEntry;
import identity.domain.model.InformationRequestMessage;
import identity.domain.model.Organization;
import identity.domain.model.OrganizationId;
import identity.domain.model.PlatformAuthority;
import identity.domain.model.VerificationStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Application service for requesting information from an organization (ADR-038 §2.5, §2.6).
 */
@Service
public class RequestOrganizationInformationService {

    private final AccountRepositoryPort accountRepository;
    private final OrganizationRepositoryPort organizationRepository;
    private final AuditLogPort auditLogPort;
    private final MongoTransactionRetryHelper retryHelper;
    private final PlatformAuthorizationPolicy policy;
    private final AuthorizationAuditActorMapper auditActorMapper;

    public RequestOrganizationInformationService(AccountRepositoryPort accountRepository,
                                                OrganizationRepositoryPort organizationRepository,
                                                AuditLogPort auditLogPort,
                                                MongoTransactionRetryHelper retryHelper,
                                                PlatformAuthorizationPolicy policy,
                                                AuthorizationAuditActorMapper auditActorMapper) {
        this.accountRepository = accountRepository;
        this.organizationRepository = organizationRepository;
        this.auditLogPort = auditLogPort;
        this.retryHelper = retryHelper;
        this.policy = policy;
        this.auditActorMapper = auditActorMapper;
    }

    public void requestOrganizationInformation(AuthorizationPrincipal principal, OrganizationId organizationId, String message) {
        Objects.requireNonNull(principal, "principal must not be null");
        Objects.requireNonNull(organizationId, "organizationId must not be null");

        // 1. Authorize platform command before any read or message parsing
        policy.authorize(principal, PlatformCommandType.REQUEST_ORGANIZATION_INFORMATION);

        // 2. Validate and build message Value Object after policy authorization, before transaction
        InformationRequestMessage requestMessage = new InformationRequestMessage(message);

        // 3. Map principal to audit actor
        AuditActor actor = auditActorMapper.toAuditActor(principal);

        // 4. Execute transactional operation with retry (D8c)
        retryHelper.executeWithRetry(() -> {
            // D6e/D8e: Revalidate caller inside transaction
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

            Organization organization = organizationRepository.findById(organizationId);
            VerificationStatus previousStatus = organization.getVerificationStatus();

            organization.requestInformation(requestMessage);

            organizationRepository.save(organization);

            // D8f: Audit log entry with exactly previousStatus, newStatus, and informationRequestPresent
            AuditLogEntry auditLog = AuditLogEntry.record(
                    UlidCreator.getUlid().toString(),
                    Instant.now(),
                    actor,
                    null,
                    organizationId,
                    AuditAction.ORGANIZATION_INFORMATION_REQUESTED,
                    Map.of(
                            "previousStatus", previousStatus.name(),
                            "newStatus", organization.getVerificationStatus().name(),
                            "informationRequestPresent", true
                    )
            );
            auditLogPort.record(auditLog);
        });
    }
}
