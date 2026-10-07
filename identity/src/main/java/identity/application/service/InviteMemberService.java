package identity.application.service;

import com.github.f4b6a3.ulid.UlidCreator;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import identity.application.authorization.OrganizationMembersPolicy;
import identity.application.authorization.AuthorizationAuditActorMapper;
import identity.application.port.out.AuditLogPort;
import identity.application.port.out.InvitationMailPort;
import identity.application.port.out.OrganizationInvitationRepositoryPort;
import identity.application.port.out.OrganizationRepositoryPort;
import identity.domain.exception.InvalidEmailFormatException;
import identity.domain.exception.OrganizationAccessDeniedException;
import identity.domain.exception.OrganizationNotFoundException;
import identity.domain.model.AccountId;
import identity.domain.model.AuditAction;
import identity.domain.model.AuditActor;
import identity.domain.model.AuditLogEntry;
import identity.domain.model.Email;
import identity.domain.model.InvitationDelivery;
import identity.domain.model.InvitationToken;
import identity.domain.model.Organization;
import identity.domain.model.OrganizationId;
import identity.domain.model.OrganizationInvitation;
import identity.domain.model.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;

/**
 * Invitar a un miembro (ADR-049 D5). {@code ADMINISTRATOR} o {@code REPRESENTATIVE} de la organización; rol
 * {@code ADMINISTRATOR} o {@code EMPLOYEE}. <b>Nunca consulta si el email tiene cuenta</b>: la respuesta es la misma
 * exista o no. Primero la transacción (revoca la pendiente anterior del mismo email y guarda la nueva con el token solo
 * como hash) y después el correo; un fallo SMTP deja {@code delivery = FAILED} y no cambia la respuesta (DD-64). El
 * token y el email nunca se registran.
 */
@Service
public class InviteMemberService {

    private static final Logger log = LoggerFactory.getLogger(InviteMemberService.class);

    public record IssuedInvitation(String invitationId, String role, Instant expiresAt) {}

    private final OrganizationMembersPolicy policy;
    private final OrganizationRepositoryPort organizations;
    private final OrganizationInvitationRepositoryPort invitations;
    private final InvitationMailPort mail;
    private final AuditLogPort auditLog;
    private final MongoTransactionRetryHelper retryHelper;
    private final AuthorizationAuditActorMapper auditActors;
    private final Clock clock;

    public InviteMemberService(OrganizationMembersPolicy policy, OrganizationRepositoryPort organizations,
                               OrganizationInvitationRepositoryPort invitations, InvitationMailPort mail,
                               AuditLogPort auditLog, MongoTransactionRetryHelper retryHelper, AuthorizationAuditActorMapper auditActors,
                               ObjectProvider<Clock> clock) {
        this.policy = policy;
        this.organizations = organizations;
        this.invitations = invitations;
        this.mail = mail;
        this.auditLog = auditLog;
        this.retryHelper = retryHelper;
        this.auditActors = auditActors;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    public IssuedInvitation invite(AuthorizationPrincipal principal, String organizationId, String email, String role) {
        policy.requireManager(principal, organizationId);
        Role invitedRole = OrganizationInvitation.invitableRole(role);
        if (email == null) {
            throw new InvalidEmailFormatException("Email cannot be null or blank");
        }
        Email invitee = new Email(email.strip().toLowerCase(Locale.ROOT));
        InvitationToken token = InvitationToken.generate();
        String invitationId = UlidCreator.getUlid().toString();
        OrganizationId orgId = new OrganizationId(organizationId);
        AuditActor actor = auditActors.toAuditActor(principal);

        record Issued(OrganizationInvitation invitation, String organizationName) {}
        Issued issued = retryHelper.executeWithRetry(() -> {
            Instant now = clock.instant();
            Organization organization;
            try {
                organization = organizations.findById(orgId);
            } catch (OrganizationNotFoundException e) {
                throw new OrganizationAccessDeniedException("Organization not manageable");
            }
            // una sola pendiente por (organización, email): el enlace anterior deja de valer (DD-62)
            invitations.revokePendingFor(orgId, OrganizationInvitation.normalize(invitee), now);
            OrganizationInvitation invitation = OrganizationInvitation.issue(invitationId, orgId, invitee, invitedRole,
                    token, new AccountId(principal.accountId()), now);
            invitations.insert(invitation);
            auditLog.record(AuditLogEntry.record(UlidCreator.getUlid().toString(), now, actor, null, orgId,
                    AuditAction.INVITATION_CREATED, Map.of("invitationId", invitationId, "role", invitedRole.name())));
            return new Issued(invitation, organization.getName());
        });

        InvitationDelivery delivery;
        try {
            mail.send(new InvitationMailPort.InvitationMail(invitationId, issued.invitation().getEmail(),
                    issued.organizationName(), invitedRole.name(), token.value(), issued.invitation().getExpiresAt()));
            delivery = InvitationDelivery.SENT;
        } catch (RuntimeException e) {
            // ni el token ni el email: solo el id y el tipo de fallo
            log.warn("No se pudo enviar el correo de la invitación {}: {}", invitationId, e.getClass().getSimpleName());
            delivery = InvitationDelivery.FAILED;
        }
        invitations.updateDelivery(invitationId, delivery);
        return new IssuedInvitation(invitationId, invitedRole.name(), issued.invitation().getExpiresAt());
    }
}
