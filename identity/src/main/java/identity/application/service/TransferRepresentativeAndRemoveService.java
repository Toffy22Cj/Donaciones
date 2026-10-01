package identity.application.service;

import com.github.f4b6a3.ulid.UlidCreator;
import identity.application.port.out.AccountRepositoryPort;
import identity.application.port.out.AuditLogPort;
import identity.application.port.out.OrganizationRepositoryPort;
import identity.domain.model.Account;
import identity.domain.model.AccountId;
import identity.domain.model.AuditAction;
import identity.domain.model.AuditActor;
import identity.domain.model.AuditLogEntry;
import identity.domain.model.Organization;
import identity.domain.model.OrganizationId;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collections;
import java.util.Objects;

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

    public void transferRepresentativeAndRemove(AuditActor actor, OrganizationId organizationId, AccountId currentRepId, AccountId newRepId) {
        Objects.requireNonNull(actor, "actor must not be null");

        retryHelper.executeWithRetry(() -> {
            Organization organization = organizationRepository.findById(organizationId);
            Account currentRepAccount = accountRepository.findById(currentRepId);

            organization.transferRepresentativeAndRemove(currentRepId, newRepId);

            boolean wasRemovedFromOrganization = organization.getMembers().stream()
                    .noneMatch(m -> m.getAccountId().equals(currentRepId));

            if (wasRemovedFromOrganization) {
                currentRepAccount.leaveOrganization();
                accountRepository.save(currentRepAccount);
            }

            organizationRepository.save(organization);

            AuditLogEntry auditLog = AuditLogEntry.record(
                    UlidCreator.getUlid().toString(),
                    Instant.now(),
                    actor,
                    newRepId,     // target
                    organizationId,
                    AuditAction.REPRESENTATIVE_TRANSFERRED,
                    Collections.emptyMap()
            );
            auditLogPort.record(auditLog);
        });
    }
}
