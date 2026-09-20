package identity.application.service;

import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.contracts.authorization.AuthorizationRole;
import identity.application.port.out.AccountRepositoryPort;
import identity.application.port.out.OrganizationRepositoryPort;
import identity.domain.exception.AccountNotFoundException;
import identity.domain.model.Account;
import identity.domain.model.AccountId;
import identity.domain.model.Email;
import identity.domain.model.Organization;
import identity.domain.model.OrganizationId;
import identity.domain.model.OrganizationType;
import identity.domain.model.PasswordHash;
import identity.domain.model.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdentityPrincipalPortImplTest {

    @Mock
    private AccountRepositoryPort accountRepositoryPort;

    @Mock
    private OrganizationRepositoryPort organizationRepositoryPort;

    private IdentityPrincipalPortImpl identityPrincipalPortImpl;

    @BeforeEach
    void setUp() {
        identityPrincipalPortImpl = new IdentityPrincipalPortImpl(accountRepositoryPort, organizationRepositoryPort);
    }

    @Test
    void testSemanticMapping() throws Exception {
        // Access private method to test mapping semantics
        Method mapRoleMethod = IdentityPrincipalPortImpl.class.getDeclaredMethod("mapRole", Role.class);
        mapRoleMethod.setAccessible(true);

        assertEquals(AuthorizationRole.ADMINISTRATOR, mapRoleMethod.invoke(identityPrincipalPortImpl, Role.ADMINISTRATOR));
        assertEquals(AuthorizationRole.REPRESENTATIVE, mapRoleMethod.invoke(identityPrincipalPortImpl, Role.REPRESENTATIVE));
        assertEquals(AuthorizationRole.EMPLOYEE, mapRoleMethod.invoke(identityPrincipalPortImpl, Role.EMPLOYEE));
    }

    @Test
    void resolvePrincipal_AccountWithoutOrganization() {
        AccountId accountId = AccountId.generate();
        Account account = Account.createAccount(new Email("test@example.com"), new PasswordHash("hash"));
        // Account has no organizationId by default

        when(accountRepositoryPort.findById(accountId)).thenReturn(account);

        AuthorizationPrincipal principal = identityPrincipalPortImpl.resolvePrincipal(accountId.value());

        assertEquals(accountId.value(), principal.accountId());
        assertNull(principal.organizationId());
        assertEquals(0, principal.roles().size());
    }

    @Test
    void resolvePrincipal_AccountWithOrganizationAndMembership() {
        AccountId repId = AccountId.generate();
        AccountId employeeId = AccountId.generate();

        Organization org = Organization.createOrganization(OrganizationType.FOUNDATION, repId);
        org.addEmployee(employeeId);
        org.assignAdministrator(employeeId);

        Account account = Account.createAccount(new Email("emp@example.com"), new PasswordHash("hash"));
        account.joinOrganization(org.getOrganizationId());

        when(accountRepositoryPort.findById(employeeId)).thenReturn(account);
        when(organizationRepositoryPort.findById(org.getOrganizationId())).thenReturn(org);

        AuthorizationPrincipal principal = identityPrincipalPortImpl.resolvePrincipal(employeeId.value());

        assertEquals(employeeId.value(), principal.accountId());
        assertEquals(org.getOrganizationId().value(), principal.organizationId());
        assertEquals(Set.of(AuthorizationRole.EMPLOYEE, AuthorizationRole.ADMINISTRATOR), principal.roles());
    }

    @Test
    void resolvePrincipal_AccountWithOrganizationButNoMatchingMembership() {
        AccountId accountId = AccountId.generate();
        AccountId otherId = AccountId.generate();

        Organization org = Organization.createOrganization(OrganizationType.FOUNDATION, otherId);

        Account account = Account.createAccount(new Email("test@example.com"), new PasswordHash("hash"));
        account.joinOrganization(org.getOrganizationId());

        when(accountRepositoryPort.findById(accountId)).thenReturn(account);
        when(organizationRepositoryPort.findById(org.getOrganizationId())).thenReturn(org);

        AuthorizationPrincipal principal = identityPrincipalPortImpl.resolvePrincipal(accountId.value());

        assertEquals(accountId.value(), principal.accountId());
        assertEquals(org.getOrganizationId().value(), principal.organizationId());
        assertEquals(true, principal.roles().isEmpty());
    }

    @Test
    void resolvePrincipal_AccountNotFound() {
        AccountId accountId = AccountId.generate();
        when(accountRepositoryPort.findById(accountId)).thenThrow(new AccountNotFoundException("Not found"));

        assertThrows(AccountNotFoundException.class, () -> {
            identityPrincipalPortImpl.resolvePrincipal(accountId.value());
        });
    }
}
