package identity.application.service;

import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.contracts.authorization.AuthorizationRole;
import com.traceability.contracts.authorization.IdentityPrincipalPort;
import identity.application.port.out.AccountRepositoryPort;
import identity.application.port.out.OrganizationRepositoryPort;
import identity.domain.model.Account;
import identity.domain.model.AccountId;
import identity.domain.model.Organization;
import identity.domain.model.Role;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class IdentityPrincipalPortImpl implements IdentityPrincipalPort {

    private final AccountRepositoryPort accountRepositoryPort;
    private final OrganizationRepositoryPort organizationRepositoryPort;

    public IdentityPrincipalPortImpl(AccountRepositoryPort accountRepositoryPort, OrganizationRepositoryPort organizationRepositoryPort) {
        this.accountRepositoryPort = accountRepositoryPort;
        this.organizationRepositoryPort = organizationRepositoryPort;
    }

    @Override
    public AuthorizationPrincipal resolvePrincipal(String accountIdStr) {
        AccountId accountId = new AccountId(accountIdStr);
        Account account = accountRepositoryPort.findById(accountId);

        if (account.getOrganizationId() == null) {
            return new AuthorizationPrincipal(
                accountIdStr,
                null,
                Collections.emptySet()
            );
        }

        String orgIdStr = account.getOrganizationId().value();
        Organization organization = organizationRepositoryPort.findById(account.getOrganizationId());

        Set<AuthorizationRole> roles = organization.getMembers().stream()
            .filter(m -> m.getAccountId().equals(accountId))
            .findFirst()
            .map(m -> m.getRoles().stream()
                .map(this::mapRole)
                .collect(Collectors.toUnmodifiableSet()))
            .orElse(Collections.emptySet());

        return new AuthorizationPrincipal(
            accountIdStr,
            orgIdStr,
            roles
        );
    }

    private AuthorizationRole mapRole(Role domainRole) {
        return switch (domainRole) {
            case ADMINISTRATOR -> AuthorizationRole.ADMINISTRATOR;
            case REPRESENTATIVE -> AuthorizationRole.REPRESENTATIVE;
            case EMPLOYEE -> AuthorizationRole.EMPLOYEE;
        };
    }
}
