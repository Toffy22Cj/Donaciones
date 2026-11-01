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
import identity.domain.model.OrganizationProfile;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Map;

/**
 * [Plan] {@code PUT /organizations/{id}/profile} (ficha R-7 §4). {@code REPRESENTATIVE} o {@code ADMINISTRATOR} de esa
 * organización, como el logo ({@link OrganizationMembersPolicy}); otro rol, otra organización o una inexistente, el
 * mismo 403 (DD-01), antes de leer nada. En una transacción: {@link Organization#updateProfile} (con P1 (b)), guardar
 * (un NIT repetido → 409, P5) y auditar {@code ORGANIZATION_PROFILE_UPDATED} solo con los nombres de los campos que
 * cambiaron, nunca sus valores. Sin cambios, no se audita (P-R7-9).
 */
@Service
public class UpdateOrganizationProfileService {

    private final OrganizationRepositoryPort organizations;
    private final AuditLogPort auditLogPort;
    private final MongoTransactionRetryHelper retryHelper;
    private final OrganizationMembersPolicy policy;
    private final AuthorizationAuditActorMapper auditActors;
    private final OrganizationProfileRules rules;
    private final Clock clock;

    public UpdateOrganizationProfileService(OrganizationRepositoryPort organizations, AuditLogPort auditLogPort,
                                            MongoTransactionRetryHelper retryHelper, OrganizationMembersPolicy policy,
                                            AuthorizationAuditActorMapper auditActors, OrganizationProfileRules rules,
                                            ObjectProvider<Clock> clock) {
        this.organizations = organizations;
        this.auditLogPort = auditLogPort;
        this.retryHelper = retryHelper;
        this.policy = policy;
        this.auditActors = auditActors;
        this.rules = rules;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    public Organization updateProfile(AuthorizationPrincipal principal, String organizationId, String name,
                                      OrganizationProfile profile) {
        policy.requireManager(principal, organizationId);
        AuditActor actor = auditActors.toAuditActor(principal);
        rules.requireKnownLocation(profile);

        return retryHelper.executeWithRetry(() -> {
            Organization organization;
            try {
                organization = organizations.findById(new OrganizationId(organizationId));
            } catch (OrganizationNotFoundException e) {
                throw new OrganizationAccessDeniedException("Organization not manageable");
            }
            List<String> changed = organization.updateProfile(name, profile);
            if (changed.isEmpty()) {
                return organization;
            }
            organizations.save(organization);
            auditLogPort.record(AuditLogEntry.record(UlidCreator.getUlid().toString(), clock.instant(), actor, null,
                    organization.getOrganizationId(), AuditAction.ORGANIZATION_PROFILE_UPDATED,
                    Map.of("changedFields", changed)));
            return organization;
        });
    }
}
