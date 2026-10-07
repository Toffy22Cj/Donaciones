package identity.application.service;

import com.traceability.contracts.authorization.AuthorizationPrincipal;
import identity.application.authorization.PlatformAuthorizationPolicy;
import identity.application.authorization.PlatformCommandType;
import identity.application.port.out.OrganizationRepositoryPort;
import identity.domain.model.Organization;
import identity.domain.model.VerificationStatus;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Cola de verificación del administrador de plataforma (autorización (3) de Carlos, §3.1; Q-v2-2): organizaciones
 * {@code PENDING_VERIFICATION} o {@code NEEDS_MORE_INFORMATION}, por orden de creación. Solo datos de la organización:
 * ni miembros ni emails. Lectura sin transacción, como {@link OrganizationMembersQuery}.
 */
@Service
public class OrganizationVerificationQueueQuery {

    /** Los estados que esperan una decisión de la plataforma. */
    public static final Set<VerificationStatus> PENDING =
            EnumSet.of(VerificationStatus.PENDING_VERIFICATION, VerificationStatus.NEEDS_MORE_INFORMATION);

    public record QueueItem(String organizationId, String name, String type, String verificationStatus,
                            String informationRequest) {}

    /** {@code next}: el id de la última organización si puede haber más; vacío en la última página. */
    public record QueuePage(List<QueueItem> items, String next) {}

    private final OrganizationRepositoryPort organizations;
    private final PlatformAuthorizationPolicy policy;

    public OrganizationVerificationQueueQuery(OrganizationRepositoryPort organizations, PlatformAuthorizationPolicy policy) {
        this.organizations = organizations;
        this.policy = policy;
    }

    /** Solo el administrador de plataforma; quien llama lo usa antes de interpretar la entrada (403 antes que 400). */
    public void authorize(AuthorizationPrincipal principal) {
        Objects.requireNonNull(principal, "principal must not be null");
        policy.authorize(principal, PlatformCommandType.READ_VERIFICATION_QUEUE);
    }

    /**
     * @param statuses subconjunto de {@link #PENDING}; otro estado es un error del llamador
     * @param afterOrganizationId id de la última organización de la página anterior, o {@code null}
     */
    public QueuePage page(AuthorizationPrincipal principal, Set<VerificationStatus> statuses, String afterOrganizationId,
                          int limit) {
        authorize(principal);
        if (statuses.isEmpty() || !PENDING.containsAll(statuses) || limit < 1) {
            throw new IllegalArgumentException("Only pending verification statuses can be listed");
        }
        // uno de más para saber si hay otra página sin una segunda consulta
        List<Organization> found = organizations.findByVerificationStatusAfter(statuses, afterOrganizationId, limit + 1);
        List<QueueItem> items = found.stream().limit(limit).map(OrganizationVerificationQueueQuery::item).toList();
        String next = found.size() > limit ? items.get(items.size() - 1).organizationId() : null;
        return new QueuePage(items, next);
    }

    private static QueueItem item(Organization o) {
        return new QueueItem(o.getOrganizationId().value(), o.getName(), o.getType().name(),
                o.getVerificationStatus().name(),
                o.getVerificationInformationRequest() == null ? null : o.getVerificationInformationRequest().value());
    }
}
