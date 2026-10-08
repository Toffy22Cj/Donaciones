package identity.application.service;

import com.github.f4b6a3.ulid.UlidCreator;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import identity.application.port.out.AccountRepositoryPort;
import identity.application.authorization.AuthorizationAuditActorMapper;
import identity.application.port.out.AuditLogPort;
import identity.application.port.out.OrganizationInvitationRepositoryPort;
import identity.application.port.out.OrganizationRepositoryPort;
import identity.domain.exception.AccountAlreadyBelongsToOrganizationException;
import identity.domain.exception.InvitationNotAcceptableException;
import identity.domain.model.Account;
import identity.domain.model.AccountId;
import identity.domain.model.AccountStatus;
import identity.domain.model.AuditAction;
import identity.domain.model.AuditActor;
import identity.domain.model.AuditLogEntry;
import identity.domain.model.InvitationToken;
import identity.domain.model.Organization;
import identity.domain.model.OrganizationInvitation;
import identity.domain.model.Role;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Aceptar una invitación (ADR-049 D4). La cuenta autenticada debe tener el email de la invitación (en minúsculas),
 * estar activa y no pertenecer a otra organización. La incorporación es la de ADR-026: {@code AddEmployee} y, si el rol
 * es {@code ADMINISTRATOR}, {@code AssignAdministrator}, en una transacción con la marca {@code ACCEPTED} condicional.
 * <p>
 * Token desconocido, caducado, revocado o usado y email distinto dan <b>la misma</b>
 * {@link InvitationNotAcceptableException} (DD-63). La cuenta con organización da 409, pero solo después de validar el
 * token y el email.
 */
@Service
public class AcceptInvitationService {

    public record Accepted(String organizationId, List<String> roles) {}

    private final OrganizationInvitationRepositoryPort invitations;
    private final AccountRepositoryPort accounts;
    private final OrganizationRepositoryPort organizations;
    private final AuditLogPort auditLog;
    private final MongoTransactionRetryHelper retryHelper;
    private final AuthorizationAuditActorMapper auditActors;
    private final Clock clock;

    public AcceptInvitationService(OrganizationInvitationRepositoryPort invitations, AccountRepositoryPort accounts,
                                   OrganizationRepositoryPort organizations, AuditLogPort auditLog,
                                   MongoTransactionRetryHelper retryHelper, AuthorizationAuditActorMapper auditActors, ObjectProvider<Clock> clock) {
        this.invitations = invitations;
        this.accounts = accounts;
        this.organizations = organizations;
        this.auditLog = auditLog;
        this.retryHelper = retryHelper;
        this.auditActors = auditActors;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    public Accepted accept(AuthorizationPrincipal principal, String token) {
        Objects.requireNonNull(principal, "principal must not be null");
        if (token == null || token.isBlank() || token.length() > 128) {
            throw notAcceptable();
        }
        String tokenHash = InvitationToken.hashOf(token.strip());
        AccountId accountId = new AccountId(principal.accountId());
        AuditActor actor = auditActors.toAuditActor(principal);

        return retryHelper.executeWithRetry(() -> {
            Instant now = clock.instant();
            OrganizationInvitation invitation = invitations.findByTokenHash(tokenHash)
                    .filter(i -> i.isOpenAt(now))
                    .orElseThrow(AcceptInvitationService::notAcceptable);
            Account account = accounts.findById(accountId);
            if (account.getStatus() != AccountStatus.ACTIVE || !invitation.isFor(account.getEmail())) {
                throw notAcceptable();
            }
            if (account.getOrganizationId() != null) {
                throw new AccountAlreadyBelongsToOrganizationException("Account already belongs to an organization");
            }
            Organization organization = organizations.findById(invitation.getOrganizationId());
            organization.addEmployee(accountId);
            if (invitation.getRole() == Role.ADMINISTRATOR) {
                organization.assignAdministrator(accountId);
            }
            account.joinOrganization(organization.getOrganizationId());
            if (!invitations.markAcceptedIfPending(invitation.getInvitationId(), accountId, now)) {
                throw notAcceptable();
            }
            organizations.save(organization);
            accounts.save(account);
            auditLog.record(AuditLogEntry.record(UlidCreator.getUlid().toString(), now, actor, accountId,
                    organization.getOrganizationId(), AuditAction.INVITATION_ACCEPTED,
                    Map.of("invitationId", invitation.getInvitationId(), "role", invitation.getRole().name())));
            auditLog.record(AuditLogEntry.record(UlidCreator.getUlid().toString(), now, actor, accountId,
                    organization.getOrganizationId(), AuditAction.EMPLOYEE_ADDED, Map.of()));
            if (invitation.getRole() == Role.ADMINISTRATOR) {
                auditLog.record(AuditLogEntry.record(UlidCreator.getUlid().toString(), now, actor, accountId,
                        organization.getOrganizationId(), AuditAction.ADMINISTRATOR_ASSIGNED, Map.of()));
            }
            List<String> roles = organization.membershipOf(accountId).orElseThrow().getRoles().stream()
                    .map(Enum::name).sorted().toList();
            return new Accepted(organization.getOrganizationId().value(), roles);
        });
    }

    private static InvitationNotAcceptableException notAcceptable() {
        return new InvitationNotAcceptableException("Invitation not acceptable");
    }
}
