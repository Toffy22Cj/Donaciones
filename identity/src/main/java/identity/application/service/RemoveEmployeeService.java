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
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

@Service
public class RemoveEmployeeService {

    private final OrganizationRepositoryPort organizationRepository;
    private final AuditLogPort auditLogPort;

    public RemoveEmployeeService(OrganizationRepositoryPort organizationRepository, AuditLogPort auditLogPort) {
        this.organizationRepository = organizationRepository;
        this.auditLogPort = auditLogPort;
    }

    @Transactional
    public void removeEmployee(OrganizationId organizationId, AccountId accountId) {
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
    }
}
