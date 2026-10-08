package identity.application.service;

import com.github.f4b6a3.ulid.UlidCreator;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import identity.application.authorization.OrganizationMembersPolicy;
import identity.application.authorization.AuthorizationAuditActorMapper;
import identity.application.port.out.AuditLogPort;
import identity.application.port.out.OrganizationInvitationRepositoryPort;
import identity.domain.exception.InvitationNotPendingException;
import identity.domain.exception.OrganizationAccessDeniedException;
import identity.domain.model.AccountId;
import identity.domain.model.AuditAction;
import identity.domain.model.AuditActor;
import identity.domain.model.AuditLogEntry;
import identity.domain.model.OrganizationInvitation;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

/**
 * Revocar una invitación (ADR-049 D6). Mismos permisos que invitar. Inexistente o de otra organización → el mismo 403;
 * ya no pendiente (aceptada, revocada o caducada) → {@link InvitationNotPendingException} (409).
 */
@Service
public class RevokeInvitationService {

    private final OrganizationMembersPolicy policy;
    private final OrganizationInvitationRepositoryPort invitations;
    private final AuditLogPort auditLog;
    private final MongoTransactionRetryHelper retryHelper;
    private final AuthorizationAuditActorMapper auditActors;
    private final Clock clock;

    public RevokeInvitationService(OrganizationMembersPolicy policy, OrganizationInvitationRepositoryPort invitations,
                                   AuditLogPort auditLog, MongoTransactionRetryHelper retryHelper, AuthorizationAuditActorMapper auditActors,
                                   ObjectProvider<Clock> clock) {
        this.policy = policy;
        this.invitations = invitations;
        this.auditLog = auditLog;
        this.retryHelper = retryHelper;
        this.auditActors = auditActors;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    public void revoke(AuthorizationPrincipal principal, String organizationId, String invitationId) {
        policy.requireManager(principal, organizationId);
        AuditActor actor = auditActors.toAuditActor(principal);
        retryHelper.executeWithRetry(() -> {
            Instant now = clock.instant();
            OrganizationInvitation invitation = invitations.findById(invitationId)
                    .filter(i -> i.getOrganizationId().value().equals(organizationId))
                    .orElseThrow(() -> new OrganizationAccessDeniedException("Invitation not manageable"));
            if (!invitation.isOpenAt(now) || !invitations.markRevokedIfPending(invitationId, now)) {
                throw new InvitationNotPendingException("Invitation is no longer pending");
            }
            auditLog.record(AuditLogEntry.record(UlidCreator.getUlid().toString(), now, actor, null,
                    invitation.getOrganizationId(), AuditAction.INVITATION_REVOKED, Map.of("invitationId", invitationId)));
        });
    }
}
