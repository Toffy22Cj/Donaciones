package identity.application.service;

import com.github.f4b6a3.ulid.UlidCreator;
import identity.application.port.out.AccountRepositoryPort;
import identity.application.port.out.AuditLogPort;
import identity.application.port.out.OrganizationRepositoryPort;
import identity.domain.model.Account;
import identity.domain.model.AccountId;
import identity.domain.model.AuditAction;
import identity.domain.model.AuditLogEntry;
import identity.domain.model.Organization;
import identity.domain.model.OrganizationId;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

@Service
public class TransferRepresentativeAndRemoveService {

    private final AccountRepositoryPort accountRepository;
    private final OrganizationRepositoryPort organizationRepository;
    private final AuditLogPort auditLogPort;
    private final MongoTransactionRetryHelper retryHelper;

    public TransferRepresentativeAndRemoveService(AccountRepositoryPort accountRepository, 
                                                  OrganizationRepositoryPort organizationRepository, 
                                                  AuditLogPort auditLogPort,
                                                  MongoTransactionRetryHelper retryHelper) {
        this.accountRepository = accountRepository;
        this.organizationRepository = organizationRepository;
        this.auditLogPort = auditLogPort;
        this.retryHelper = retryHelper;
    }

    public void transferRepresentativeAndRemove(OrganizationId organizationId, AccountId currentRepId, AccountId newRepId) {
        retryHelper.executeWithRetry(() -> {
            Organization organization = organizationRepository.findById(organizationId);
            Account currentRepAccount = accountRepository.findById(currentRepId);
            
            // Only strictly needed to verify newRep exists if the invariant doesn't cover it,
            // but the domain model TransferRepresentativeAndRemove checks if newRep is a member.
            // If they are a member, they MUST exist in the DB, so we don't necessarily have to load newRepAccount 
            // to modify it, unless we need to change its organizationId (but it already is a member, so orgId is already set).
            // So we only load currentRepAccount to possibly nullify its organizationId.

            organization.transferRepresentativeAndRemove(currentRepId, newRepId);

            boolean wasRemovedFromOrganization = organization.getMembers().stream()
                    .noneMatch(m -> m.getAccountId().equals(currentRepId));

            if (wasRemovedFromOrganization) {
                currentRepAccount.leaveOrganization();
                accountRepository.save(currentRepAccount);
            }

            organizationRepository.save(organization);

            AuditLogEntry auditLog = new AuditLogEntry(
                    UlidCreator.getUlid().toString(),
                    Instant.now(),
                    currentRepId, // actor
                    newRepId,     // target
                    organizationId,
                    AuditAction.REPRESENTATIVE_TRANSFERRED,
                    Map.of()
            );
            auditLogPort.record(auditLog);
        });
    }
}
