package identity.application.service;

import com.traceability.contracts.authorization.AuthorizationPrincipal;
import identity.application.authorization.OrganizationMembersPolicy;
import identity.application.port.out.OrganizationInvitationRepositoryPort;
import identity.domain.model.OrganizationId;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Invitaciones pendientes y sin caducar de una organización (ADR-049 D6, DD-65): email <b>enmascarado</b>, rol, fechas
 * y resultado del envío. Nunca el token ni su hash. Mismos permisos que invitar. Una página de {@value #LIMIT}.
 */
@Service
public class OrganizationInvitationsQuery {

    public static final int LIMIT = 100;

    public record PendingInvitation(String invitationId, String emailMasked, String role, Instant createdAt,
                                    Instant expiresAt, String delivery) {}

    private final OrganizationMembersPolicy policy;
    private final OrganizationInvitationRepositoryPort invitations;
    private final Clock clock;

    public OrganizationInvitationsQuery(OrganizationMembersPolicy policy, OrganizationInvitationRepositoryPort invitations,
                                        ObjectProvider<Clock> clock) {
        this.policy = policy;
        this.invitations = invitations;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    public List<PendingInvitation> pending(AuthorizationPrincipal principal, String organizationId) {
        policy.requireManager(principal, organizationId);
        Instant now = clock.instant();
        return invitations.findPendingByOrganization(new OrganizationId(organizationId), LIMIT).stream()
                .filter(i -> i.isOpenAt(now))
                .map(i -> new PendingInvitation(i.getInvitationId(), i.maskedEmail(), i.getRole().name(),
                        i.getCreatedAt(), i.getExpiresAt(), i.getDelivery().name()))
                .toList();
    }
}
