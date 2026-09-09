package identity.domain.model;

import identity.domain.exception.AccountAlreadyBelongsToOrganizationException;
import identity.domain.exception.InactiveAccountException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AccountTest {

    @Test
    void testCreateAccount() {
        Email email = new Email("test@example.com");
        PasswordHash passwordHash = new PasswordHash("hashed-password");

        Account account = Account.createAccount(email, passwordHash);

        assertNotNull(account.getAccountId());
        assertEquals(email, account.getEmail());
        assertEquals(passwordHash, account.getPasswordHash());
        assertEquals(AccountStatus.ACTIVE, account.getStatus());
        assertNull(account.getOrganizationId());
    }

    @Test
    void testChangeCredentials_WhenActive() {
        Account account = Account.createAccount(new Email("test@example.com"), new PasswordHash("old-hash"));
        PasswordHash newHash = new PasswordHash("new-hash");

        account.changeCredentials(newHash);

        assertEquals(newHash, account.getPasswordHash());
    }

    @Test
    void testChangeCredentials_WhenInactive_ThrowsException() {
        Account account = Account.createAccount(new Email("test@example.com"), new PasswordHash("old-hash"));
        account.deactivate();

        PasswordHash newHash = new PasswordHash("new-hash");

        assertThrows(InactiveAccountException.class, () -> account.changeCredentials(newHash));
    }

    @Test
    void testDeactivate_IsIdempotentAndNoOp() {
        Account account = Account.createAccount(new Email("test@example.com"), new PasswordHash("hash"));
        assertEquals(AccountStatus.ACTIVE, account.getStatus());

        account.deactivate();
        assertEquals(AccountStatus.INACTIVE, account.getStatus());

        // Idempotent call
        account.deactivate();
        assertEquals(AccountStatus.INACTIVE, account.getStatus());
    }

    @Test
    void testReactivate_IsIdempotentAndNoOp() {
        Account account = Account.createAccount(new Email("test@example.com"), new PasswordHash("hash"));
        account.deactivate();
        assertEquals(AccountStatus.INACTIVE, account.getStatus());

        account.reactivate();
        assertEquals(AccountStatus.ACTIVE, account.getStatus());

        // Idempotent call
        account.reactivate();
        assertEquals(AccountStatus.ACTIVE, account.getStatus());
    }

    @Test
    void testJoinOrganization_WhenNoOrganization_Succeeds() {
        Account account = Account.createAccount(new Email("test@example.com"), new PasswordHash("hash"));
        OrganizationId orgId = OrganizationId.generate();

        account.joinOrganization(orgId);

        assertEquals(orgId, account.getOrganizationId());
    }

    @Test
    void testJoinOrganization_WhenAlreadyHasOrganization_ThrowsException() {
        Account account = Account.createAccount(new Email("test@example.com"), new PasswordHash("hash"));
        OrganizationId orgId1 = OrganizationId.generate();
        account.joinOrganization(orgId1);

        OrganizationId orgId2 = OrganizationId.generate();

        assertThrows(AccountAlreadyBelongsToOrganizationException.class, () -> account.joinOrganization(orgId2));
        assertEquals(orgId1, account.getOrganizationId());
    }

    @Test
    void testLeaveOrganization() {
        Account account = Account.createAccount(new Email("test@example.com"), new PasswordHash("hash"));
        OrganizationId orgId = OrganizationId.generate();
        account.joinOrganization(orgId);

        account.leaveOrganization();

        assertNull(account.getOrganizationId());
    }

    @Test
    void testReconstitute() {
        AccountId id = AccountId.generate();
        Email email = new Email("existing@example.com");
        PasswordHash hash = new PasswordHash("existing-hash");
        OrganizationId orgId = OrganizationId.generate();

        Account account = Account.reconstitute(id, email, hash, AccountStatus.INACTIVE, orgId);

        assertEquals(id, account.getAccountId());
        assertEquals(email, account.getEmail());
        assertEquals(hash, account.getPasswordHash());
        assertEquals(AccountStatus.INACTIVE, account.getStatus());
        assertEquals(orgId, account.getOrganizationId());
    }
}
