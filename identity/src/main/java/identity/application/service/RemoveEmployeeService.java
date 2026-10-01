package identity.application.service;

import com.github.f4b6a3.ulid.UlidCreator;
import identity.application.port.out.AuditLogPort;
import identity.application.port.out.OrganizationRepositoryPort;
import identity.domain.model.AccountId;
import identity.domain.model.AuditAction;
import identity.domain.model.AuditLogEntry;
import identity.domain.model.Organization;
import identity.domain.model.OrganizationId;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

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

    public void removeEmployee(OrganizationId organizationId, AccountId accountId) {
        retryHelper.executeWithRetry(() -> {
            Organization organization = organizationRepository.findById(organizationId);

            boolean mutated = organization.removeEmployee(accountId);

            if (mutated) {
                organizationRepository.save(organization);

                AuditLogEntry auditLog = new AuditLogEntry(
                        UlidCreator.getUlid().toString(),
                        Instant.now(),
                        accountId, 
                        accountId,
                        organizationId,
                        AuditAction.EMPLOYEE_REMOVED,
                        Map.of()
                );
                auditLogPort.record(auditLog);
            }
        });
    }
}
