package identity.application.service;

import com.github.f4b6a3.ulid.UlidCreator;
import identity.application.port.out.AccountRepositoryPort;
import identity.application.port.out.AuditLogPort;
import identity.application.port.out.OrganizationRepositoryPort;
import identity.domain.exception.AccountAlreadyBelongsToOrganizationException;
import identity.domain.exception.AccountNotFoundException;
import identity.domain.model.Account;
import identity.domain.model.AccountId;
import identity.domain.model.AuditAction;
import identity.domain.model.AuditLogEntry;
import identity.domain.model.Organization;
import identity.domain.model.OrganizationType;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

@Service
public class CreateOrganizationService {

    private final AccountRepositoryPort accountRepository;
    private final OrganizationRepositoryPort organizationRepository;
    private final AuditLogPort auditLogPort;
    private final MongoTransactionRetryHelper retryHelper;

    public CreateOrganizationService(AccountRepositoryPort accountRepository, 
                                     OrganizationRepositoryPort organizationRepository, 
                                     AuditLogPort auditLogPort,
                                     MongoTransactionRetryHelper retryHelper) {
        this.accountRepository = accountRepository;
        this.organizationRepository = organizationRepository;
        this.auditLogPort = auditLogPort;
        this.retryHelper = retryHelper;
    }

    public Organization createOrganization(OrganizationType type, AccountId initialRepresentativeAccountId) {
        return retryHelper.executeWithRetry(() -> {
            Account account = accountRepository.findById(initialRepresentativeAccountId);

            if (account.getOrganizationId() != null) {
                throw new AccountAlreadyBelongsToOrganizationException("Account already belongs to an organization");
            }

            Organization organization = Organization.createOrganization(type, initialRepresentativeAccountId);
            account.joinOrganization(organization.getOrganizationId());

            organizationRepository.save(organization);
            accountRepository.save(account);

            AuditLogEntry auditLog = new AuditLogEntry(
                    UlidCreator.getUlid().toString(),
                    Instant.now(),
                    initialRepresentativeAccountId,
                    initialRepresentativeAccountId,
                    organization.getOrganizationId(),
                    AuditAction.ORGANIZATION_CREATED,
                    Map.of("type", type.name())
            );
            auditLogPort.record(auditLog);

            return organization;
        });
    }
}
