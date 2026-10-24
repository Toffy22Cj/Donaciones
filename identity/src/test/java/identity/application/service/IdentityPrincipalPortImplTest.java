package identity.application.service;

import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.contracts.authorization.AuthorizationRole;
import identity.application.port.out.AccountRepositoryPort;
import identity.application.port.out.OrganizationRepositoryPort;
import identity.domain.exception.AccountNotFoundException;
import identity.domain.exception.CredentialsRevokedException;
import identity.domain.exception.InactiveAccountException;
import identity.domain.model.Account;
import identity.domain.model.AccountId;
import identity.domain.model.AccountStatus;
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
import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
        assertNull(principal.platformAuthority());
    }

    @Test
    void resolvePrincipal_AccountWithPlatformAuthority() {
        AccountId accountId = AccountId.generate();
        Account account = Account.reconstitute(
                accountId,
                new Email("admin@example.com"),
                new PasswordHash("hash"),
                AccountStatus.ACTIVE,
                null,
                identity.domain.model.PlatformAuthority.ADMINISTRATOR
        );

        when(accountRepositoryPort.findById(accountId)).thenReturn(account);

        AuthorizationPrincipal principal = identityPrincipalPortImpl.resolvePrincipal(accountId.value());

        assertEquals(accountId.value(), principal.accountId());
        assertNull(principal.organizationId());
        assertEquals(0, principal.roles().size());
        assertEquals(com.traceability.contracts.authorization.PlatformAuthority.ADMINISTRATOR, principal.platformAuthority());
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
        assertNull(principal.platformAuthority());
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
        assertNull(principal.platformAuthority());
    }

    @Test
    void resolvePrincipal_AccountNotFound() {
        AccountId accountId = AccountId.generate();
        when(accountRepositoryPort.findById(accountId)).thenThrow(new AccountNotFoundException("Not found"));

        assertThrows(AccountNotFoundException.class, () -> {
            identityPrincipalPortImpl.resolvePrincipal(accountId.value());
        });
    }

    @Test
    void resolvePrincipal_InactiveAccountWithoutOrganization_Throws() {
        AccountId accountId = AccountId.generate();
        Account account = Account.createAccount(new Email("test@example.com"), new PasswordHash("hash"));
        account.deactivate(java.time.Instant.parse("2026-10-24T12:00:00Z"));

        when(accountRepositoryPort.findById(accountId)).thenReturn(account);

        assertThrows(InactiveAccountException.class, () -> {
            identityPrincipalPortImpl.resolvePrincipal(accountId.value());
        });

        verify(organizationRepositoryPort, never()).findById(any());
    }

    @Test
    void resolvePrincipal_InactiveAccountWithOrganization_Throws() {
        AccountId repId = AccountId.generate();
        AccountId employeeId = AccountId.generate();

        Organization org = Organization.createOrganization(OrganizationType.FOUNDATION, repId);
        org.addEmployee(employeeId);
        org.assignAdministrator(employeeId);

        Account account = Account.createAccount(new Email("emp@example.com"), new PasswordHash("hash"));
        account.joinOrganization(org.getOrganizationId());
        account.deactivate(java.time.Instant.parse("2026-10-24T12:00:00Z"));

        when(accountRepositoryPort.findById(employeeId)).thenReturn(account);
        lenient().when(organizationRepositoryPort.findById(org.getOrganizationId())).thenReturn(org);

        assertThrows(InactiveAccountException.class, () -> {
            identityPrincipalPortImpl.resolvePrincipal(employeeId.value());
        });

        verify(organizationRepositoryPort, never()).findById(any());
    }

    // --- ADR-050 D4: resolveAuthenticatedPrincipal ---

    private static final Instant CNB = Instant.parse("2026-10-24T12:00:01Z");

    private static Account accountWithCredentialsNotBefore(AccountId accountId, AccountStatus status, Instant cnb) {
        return Account.reconstitute(accountId, new Email("test@example.com"), new PasswordHash("hash"),
                status, null, null, cnb);
    }

    @Test
    void resolveAuthenticatedPrincipal_iatBeforeCredentialsNotBefore_throwsCredentialsRevoked() {
        AccountId accountId = AccountId.generate();
        when(accountRepositoryPort.findById(accountId))
                .thenReturn(accountWithCredentialsNotBefore(accountId, AccountStatus.ACTIVE, CNB));

        assertThrows(CredentialsRevokedException.class,
                () -> identityPrincipalPortImpl.resolveAuthenticatedPrincipal(accountId.value(), CNB.minusSeconds(1)));

        verify(accountRepositoryPort, times(1)).findById(accountId);
        verify(organizationRepositoryPort, never()).findById(any());
    }

    @Test
    void resolveAuthenticatedPrincipal_iatEqualToCredentialsNotBefore_isAccepted() {
        AccountId accountId = AccountId.generate();
        when(accountRepositoryPort.findById(accountId))
                .thenReturn(accountWithCredentialsNotBefore(accountId, AccountStatus.ACTIVE, CNB));

        AuthorizationPrincipal principal = identityPrincipalPortImpl.resolveAuthenticatedPrincipal(accountId.value(), CNB);

        assertEquals(accountId.value(), principal.accountId());
        assertNull(principal.organizationId());
        assertEquals(0, principal.roles().size());
        verify(accountRepositoryPort, times(1)).findById(accountId);
    }

    @Test
    void resolveAuthenticatedPrincipal_withoutCredentialsNotBefore_isAccepted() {
        AccountId accountId = AccountId.generate();
        when(accountRepositoryPort.findById(accountId))
                .thenReturn(accountWithCredentialsNotBefore(accountId, AccountStatus.ACTIVE, null));

        AuthorizationPrincipal principal = identityPrincipalPortImpl.resolveAuthenticatedPrincipal(
                accountId.value(), Instant.parse("2000-01-01T00:00:00Z"));

        assertEquals(accountId.value(), principal.accountId());
    }

    @Test
    void resolveAuthenticatedPrincipal_inactiveAccount_throwsInactiveEvenWithValidIat() {
        AccountId accountId = AccountId.generate();
        when(accountRepositoryPort.findById(accountId))
                .thenReturn(accountWithCredentialsNotBefore(accountId, AccountStatus.INACTIVE, CNB));

        assertThrows(InactiveAccountException.class,
                () -> identityPrincipalPortImpl.resolveAuthenticatedPrincipal(accountId.value(), CNB.plusSeconds(60)));

        verify(organizationRepositoryPort, never()).findById(any());
    }

    @Test
    void resolveAuthenticatedPrincipal_withOrganization_returnsSameRolesAsResolvePrincipal() {
        AccountId repId = AccountId.generate();
        AccountId employeeId = AccountId.generate();
        Organization org = Organization.createOrganization(OrganizationType.FOUNDATION, repId);
        org.addEmployee(employeeId);
        Account account = accountWithCredentialsNotBefore(employeeId, AccountStatus.ACTIVE, CNB);
        account.joinOrganization(org.getOrganizationId());
        when(accountRepositoryPort.findById(employeeId)).thenReturn(account);
        when(organizationRepositoryPort.findById(org.getOrganizationId())).thenReturn(org);

        AuthorizationPrincipal principal = identityPrincipalPortImpl.resolveAuthenticatedPrincipal(employeeId.value(), CNB);

        assertEquals(org.getOrganizationId().value(), principal.organizationId());
        assertEquals(Set.of(AuthorizationRole.EMPLOYEE), principal.roles());
    }

    @Test
    void resolvePrincipal_ignoresCredentialsNotBefore() {
        AccountId accountId = AccountId.generate();
        when(accountRepositoryPort.findById(accountId))
                .thenReturn(accountWithCredentialsNotBefore(accountId, AccountStatus.ACTIVE, CNB));

        AuthorizationPrincipal principal = identityPrincipalPortImpl.resolvePrincipal(accountId.value());

        assertEquals(accountId.value(), principal.accountId());
    }
}
