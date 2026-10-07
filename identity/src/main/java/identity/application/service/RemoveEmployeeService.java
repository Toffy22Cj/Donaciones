package identity.application.service;

import com.github.f4b6a3.ulid.UlidCreator;
import identity.application.port.out.AuditLogPort;
import identity.application.port.out.OrganizationRepositoryPort;
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
public class RemoveEmployeeService {

    private final OrganizationRepositoryPort organizationRepository;
    private final AuditLogPort auditLogPort;
    private final MongoTransactionRetryHelper retryHelper;

    public RemoveEmployeeService(OrganizationRepositoryPort organizationRepository, 
                                 AuditLogPort auditLogPort,
                                 MongoTransactionRetryHelper retryHelper) {
        this.organizationRepository = organizationRepository;
        this.auditLogPort = auditLogPort;
        this.retryHelper = retryHelper;
    }

    public void removeEmployee(AuditActor actor, OrganizationId organizationId, AccountId accountId) {
        Objects.requireNonNull(actor, "actor must not be null");

        retryHelper.executeWithRetry(() -> {
            Organization organization = organizationRepository.findById(organizationId);

            boolean mutated = organization.removeEmployee(accountId);

            if (mutated) {
                organizationRepository.save(organization);

                AuditLogEntry auditLog = AuditLogEntry.record(
                        UlidCreator.getUlid().toString(),
                        Instant.now(),
                        actor,
                        accountId, 
                        organizationId,
                        AuditAction.EMPLOYEE_REMOVED,
                        Collections.emptyMap()
                );
                auditLogPort.record(auditLog);
            }
        });
    }
}
