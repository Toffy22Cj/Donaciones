package identity.application.service;

import com.github.f4b6a3.ulid.UlidCreator;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import identity.application.authorization.AuthorizationAuditActorMapper;
import identity.application.authorization.OrganizationMembersPolicy;
import identity.application.port.out.AuditLogPort;
import identity.application.port.out.OrganizationRepositoryPort;
import identity.domain.exception.OrganizationAccessDeniedException;
import identity.domain.exception.OrganizationNotFoundException;
import identity.domain.model.AuditAction;
import identity.domain.model.AuditActor;
import identity.domain.model.AuditLogEntry;
import identity.domain.model.Organization;
import identity.domain.model.OrganizationId;
import identity.domain.model.VerificationStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Map;

/**
 * [Plan] {@code POST /organizations/{id}/verification/resubmit} (ficha R-7 §5, P8 (a)): la organización responde a la
 * solicitud de información y vuelve a la cola de la plataforma. Misma autorización que el perfil. En una transacción:
 * {@link Organization#resubmitForVerification} (desde otro estado, 409 {@code VerificationResubmitNotAllowed}), guardar
 * y auditar {@code ORGANIZATION_VERIFICATION_RESUBMITTED} con el actor del principal, sin datos (P-R7-10).
 */
@Service
public class ResubmitOrganizationVerificationService {

    private final OrganizationRepositoryPort organizations;
    private final AuditLogPort auditLogPort;
    private final MongoTransactionRetryHelper retryHelper;
    private final OrganizationMembersPolicy policy;
    private final AuthorizationAuditActorMapper auditActors;
    private final Clock clock;

    public ResubmitOrganizationVerificationService(OrganizationRepositoryPort organizations, AuditLogPort auditLogPort,
                                                   MongoTransactionRetryHelper retryHelper,
                                                   OrganizationMembersPolicy policy,
                                                   AuthorizationAuditActorMapper auditActors,
                                                   ObjectProvider<Clock> clock) {
        this.organizations = organizations;
        this.auditLogPort = auditLogPort;
        this.retryHelper = retryHelper;
        this.policy = policy;
        this.auditActors = auditActors;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    public VerificationStatus resubmit(AuthorizationPrincipal principal, String organizationId) {
        policy.requireManager(principal, organizationId);
        AuditActor actor = auditActors.toAuditActor(principal);

        return retryHelper.executeWithRetry(() -> {
            Organization organization;
            try {
                organization = organizations.findById(new OrganizationId(organizationId));
            } catch (OrganizationNotFoundException e) {
                throw new OrganizationAccessDeniedException("Organization not manageable");
            }
            organization.resubmitForVerification();
            organizations.save(organization);
            auditLogPort.record(AuditLogEntry.record(UlidCreator.getUlid().toString(), clock.instant(), actor, null,
                    organization.getOrganizationId(), AuditAction.ORGANIZATION_VERIFICATION_RESUBMITTED, Map.of()));
            return organization.getVerificationStatus();
        });
    }
}
