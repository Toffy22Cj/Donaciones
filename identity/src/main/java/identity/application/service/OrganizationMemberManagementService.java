package identity.application.service;

import com.github.f4b6a3.ulid.UlidCreator;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.contracts.campaign.CampaignResponsibilityPort;
import identity.application.authorization.OrganizationMembersPolicy;
import identity.application.port.out.AccountRepositoryPort;
import identity.application.authorization.AuthorizationAuditActorMapper;
import identity.application.port.out.AuditLogPort;
import identity.application.port.out.OrganizationRepositoryPort;
import identity.domain.exception.ActiveCampaignResponsibleException;
import identity.domain.exception.OrganizationAccessDeniedException;
import identity.domain.exception.OrganizationNotFoundException;
import identity.domain.model.Account;
import identity.domain.model.AccountId;
import identity.domain.model.AuditAction;
import identity.domain.model.AuditActor;
import identity.domain.model.AuditLogEntry;
import identity.domain.model.Membership;
import identity.domain.model.Organization;
import identity.domain.model.OrganizationId;
import identity.domain.model.OrganizationInvitation;
import identity.domain.model.Role;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cambiar el rol de un miembro y quitarlo de la organización (ADR-049 D7–D9; autorización (3) de Carlos, §3.3).
 * {@code ADMINISTRATOR} o {@code REPRESENTATIVE} de esa organización; un no miembro o uno de otra organización da el
 * mismo 403.
 * <ul>
 *   <li>Nunca sin {@code REPRESENTATIVE}: no se quita (409, ADR-026) y sus roles solo los cambia él mismo (403, DD-66);</li>
 *   <li>no se quita ni se degrada a un responsable activo de una convocatoria abierta sin reemplazarlo antes (409),
 *   consultado por {@link CampaignResponsibilityPort} (DD-67).</li>
 * </ul>
 */
@Service
public class OrganizationMemberManagementService {

    public record MemberRoles(String accountId, List<String> roles) {}

    private final OrganizationMembersPolicy policy;
    private final OrganizationRepositoryPort organizations;
    private final AccountRepositoryPort accounts;
    private final CampaignResponsibilityPort responsibilities;
    private final AuditLogPort auditLog;
    private final MongoTransactionRetryHelper retryHelper;
    private final AuthorizationAuditActorMapper auditActors;

    public OrganizationMemberManagementService(OrganizationMembersPolicy policy, OrganizationRepositoryPort organizations,
                                               AccountRepositoryPort accounts, CampaignResponsibilityPort responsibilities,
                                               AuditLogPort auditLog, MongoTransactionRetryHelper retryHelper, AuthorizationAuditActorMapper auditActors) {
        this.policy = policy;
        this.organizations = organizations;
        this.accounts = accounts;
        this.responsibilities = responsibilities;
        this.auditLog = auditLog;
        this.retryHelper = retryHelper;
        this.auditActors = auditActors;
    }

    public MemberRoles changeRole(AuthorizationPrincipal principal, String organizationId, String accountId, String role) {
        policy.requireManager(principal, organizationId);
        Role target = OrganizationInvitation.invitableRole(role);
        AccountId member = new AccountId(accountId);
        AuditActor actor = auditActors.toAuditActor(principal);
        return retryHelper.executeWithRetry(() -> {
            Organization organization = organization(organizationId);
            Membership membership = memberOf(organization, member);
            if (membership.hasRole(Role.REPRESENTATIVE) && !accountId.equals(principal.accountId())) {
                throw new OrganizationAccessDeniedException("Only the representative changes their own roles");
            }
            boolean hadEmployee = membership.hasRole(Role.EMPLOYEE);
            if (target == Role.EMPLOYEE && membership.hasRole(Role.ADMINISTRATOR)
                    && responsibilities.activeActingRoles(organizationId, accountId).contains("ADMINISTRATOR")) {
                throw new ActiveCampaignResponsibleException("Member is an active campaign responsible as ADMINISTRATOR");
            }
            organization.changeMemberRole(member, target);
            organizations.save(organization);
            Instant now = Instant.now();
            if (target == Role.ADMINISTRATOR) {
                audit(actor, member, organization, AuditAction.ADMINISTRATOR_ASSIGNED, now);
            } else {
                if (!hadEmployee) {
                    audit(actor, member, organization, AuditAction.EMPLOYEE_ADDED, now);
                }
                audit(actor, member, organization, AuditAction.ADMINISTRATOR_REMOVED, now);
            }
            return new MemberRoles(accountId, memberOf(organization, member).getRoles().stream()
                    .map(Enum::name).sorted().toList());
        });
    }

    public void remove(AuthorizationPrincipal principal, String organizationId, String accountId) {
        policy.requireManager(principal, organizationId);
        AccountId member = new AccountId(accountId);
        AuditActor actor = auditActors.toAuditActor(principal);
        retryHelper.executeWithRetry(() -> {
            Organization organization = organization(organizationId);
            memberOf(organization, member);
            // el representante: RepresentativeTransferRequiredException (409) del dominio, antes que la convocatoria
            Set<String> acting = organization.membershipOf(member).orElseThrow().hasRole(Role.REPRESENTATIVE)
                    ? Set.of() : responsibilities.activeActingRoles(organizationId, accountId);
            if (!acting.isEmpty()) {
                throw new ActiveCampaignResponsibleException("Member is an active campaign responsible");
            }
            organization.removeMemberFromOrganization(member);
            Account account = accounts.findById(member);
            account.leaveOrganization();
            organizations.save(organization);
            accounts.save(account);
            audit(actor, member, organization, AuditAction.MEMBER_REMOVED, Instant.now());
        });
    }

    private Organization organization(String organizationId) {
        try {
            return organizations.findById(new OrganizationId(organizationId));
        } catch (OrganizationNotFoundException e) {
            throw new OrganizationAccessDeniedException("Organization not manageable");
        }
    }

    private static Membership memberOf(Organization organization, AccountId member) {
        return organization.membershipOf(member)
                .orElseThrow(() -> new OrganizationAccessDeniedException("Account is not a member of this organization"));
    }

    private void audit(AuditActor actor, AccountId member, Organization organization, AuditAction action, Instant now) {
        auditLog.record(AuditLogEntry.record(UlidCreator.getUlid().toString(), now, actor, member,
                organization.getOrganizationId(), action, Map.of()));
    }
}
