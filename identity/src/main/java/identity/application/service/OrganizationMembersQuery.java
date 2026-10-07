package identity.application.service;

import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.contracts.authorization.AuthorizationRole;
import identity.application.port.out.AccountRepositoryPort;
import identity.application.port.out.OrganizationRepositoryPort;
import identity.domain.exception.AccountNotFoundException;
import identity.domain.exception.OrganizationAccessDeniedException;
import identity.domain.exception.OrganizationNotFoundException;
import identity.domain.model.Membership;
import identity.domain.model.Organization;
import identity.domain.model.OrganizationId;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * Miembros de una organización (ID-12; P2.7): {@code accountId}, roles y estado de la cuenta. <b>Sin email</b> ni
 * ningún otro dato personal. Solo {@code ADMINISTRATOR} o {@code REPRESENTATIVE} de esa organización; otra organización
 * o una inexistente dan la misma excepción. Lectura sin transacción, como {@link OrganizationPublicNameQuery}.
 */
@Service
public class OrganizationMembersQuery {

    public record MemberView(String accountId, List<String> roles, String status) {}

    private final OrganizationRepositoryPort organizations;
    private final AccountRepositoryPort accounts;

    public OrganizationMembersQuery(OrganizationRepositoryPort organizations, AccountRepositoryPort accounts) {
        this.organizations = organizations;
        this.accounts = accounts;
    }

    public List<MemberView> members(AuthorizationPrincipal principal, String organizationId) {
        if (principal == null || organizationId == null || !organizationId.equals(principal.organizationId())
                || principal.roles() == null || !(principal.roles().contains(AuthorizationRole.ADMINISTRATOR)
                || principal.roles().contains(AuthorizationRole.REPRESENTATIVE))) {
            throw new OrganizationAccessDeniedException("Reading members requires ADMINISTRATOR or REPRESENTATIVE");
        }
        Organization organization;
        try {
            organization = organizations.findById(new OrganizationId(organizationId));
        } catch (OrganizationNotFoundException e) {
            throw new OrganizationAccessDeniedException("Organization not readable");
        }
        return organization.getMembers().stream()
                .sorted(Comparator.comparing(m -> m.getAccountId().value()))
                .map(this::view).toList();
    }

    private MemberView view(Membership m) {
        String status;
        try {
            status = accounts.findById(m.getAccountId()).getStatus().name();
        } catch (AccountNotFoundException e) {
            status = null;
        }
        return new MemberView(m.getAccountId().value(), m.getRoles().stream().map(Enum::name).sorted().toList(), status);
    }
}
