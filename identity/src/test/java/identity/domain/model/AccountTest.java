package identity.domain.model;

import identity.domain.exception.AccountAlreadyBelongsToOrganizationException;
import identity.domain.exception.InactiveAccountException;
import identity.domain.exception.PlatformAdministratorDeactivationException;
import identity.domain.exception.PlatformAuthorityAlreadyGrantedException;
import identity.domain.exception.PlatformAuthorityNotHeldException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class AccountTest {

    private static final Instant NOW = Instant.parse("2026-10-24T12:00:00.750Z");

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
        assertNull(account.getPlatformAuthority());
    }

    @Test
    void testChangeCredentials_WhenActive() {
        Account account = Account.createAccount(new Email("test@example.com"), new PasswordHash("old-hash"));
        PasswordHash newHash = new PasswordHash("new-hash");

        account.changeCredentials(newHash, NOW);

        assertEquals(newHash, account.getPasswordHash());
    }

    @Test
    void testChangeCredentials_WhenInactive_ThrowsException() {
        Account account = Account.createAccount(new Email("test@example.com"), new PasswordHash("old-hash"));
        account.deactivate(NOW);

        PasswordHash newHash = new PasswordHash("new-hash");

        assertThrows(InactiveAccountException.class, () -> account.changeCredentials(newHash, NOW));
    }

    @Test
    void testDeactivate_IsIdempotentAndNoOp() {
        Account account = Account.createAccount(new Email("test@example.com"), new PasswordHash("hash"));
        assertEquals(AccountStatus.ACTIVE, account.getStatus());

        account.deactivate(NOW);
        assertEquals(AccountStatus.INACTIVE, account.getStatus());

        // Idempotent call
        account.deactivate(NOW);
        assertEquals(AccountStatus.INACTIVE, account.getStatus());
    }

    @Test
    void testDeactivate_WithPlatformAuthority_ThrowsException() {
        AccountId id = AccountId.generate();
        Email email = new Email("admin@example.com");
        PasswordHash hash = new PasswordHash("hash");
        Account account = Account.reconstitute(id, email, hash, AccountStatus.ACTIVE, null, PlatformAuthority.ADMINISTRATOR);

        assertThrows(PlatformAdministratorDeactivationException.class, () -> account.deactivate(NOW));
        assertEquals(AccountStatus.ACTIVE, account.getStatus());
        assertEquals(PlatformAuthority.ADMINISTRATOR, account.getPlatformAuthority());
    }

    @Test
    void testDeactivate_WithoutPlatformAuthority_Succeeds() {
        Account account = Account.createAccount(new Email("user@example.com"), new PasswordHash("hash"));
        assertNull(account.getPlatformAuthority());

        assertTrue(account.deactivate(NOW));
        assertEquals(AccountStatus.INACTIVE, account.getStatus());
    }

    @Test
    void testReactivate_IsIdempotentAndNoOp() {
        Account account = Account.createAccount(new Email("test@example.com"), new PasswordHash("hash"));
        account.deactivate(NOW);
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

        Account account = Account.reconstitute(id, email, hash, AccountStatus.INACTIVE, orgId, null);

        assertEquals(id, account.getAccountId());
        assertEquals(email, account.getEmail());
        assertEquals(hash, account.getPasswordHash());
        assertEquals(AccountStatus.INACTIVE, account.getStatus());
        assertEquals(orgId, account.getOrganizationId());
        assertNull(account.getPlatformAuthority());
    }

    @Test
    void grantPlatformAuthority_success() {
        Account account = Account.createAccount(new Email("admin@example.com"), new PasswordHash("hash"));
        assertNull(account.getPlatformAuthority());

        account.grantPlatformAuthority();

        assertEquals(PlatformAuthority.ADMINISTRATOR, account.getPlatformAuthority());
    }

    @Test
    void grantPlatformAuthority_onInactiveAccount_throwsInactiveAccountException() {
        Account account = Account.createAccount(new Email("admin@example.com"), new PasswordHash("hash"));
        account.deactivate(NOW);
        assertEquals(AccountStatus.INACTIVE, account.getStatus());

        assertThrows(InactiveAccountException.class, account::grantPlatformAuthority);
        assertNull(account.getPlatformAuthority());
    }

    @Test
    void grantPlatformAuthority_alreadyGranted_throwsAlreadyGrantedException() {
        Account account = Account.createAccount(new Email("admin@example.com"), new PasswordHash("hash"));
        account.grantPlatformAuthority();
        assertEquals(PlatformAuthority.ADMINISTRATOR, account.getPlatformAuthority());

        assertThrows(PlatformAuthorityAlreadyGrantedException.class, account::grantPlatformAuthority);
        assertEquals(PlatformAuthority.ADMINISTRATOR, account.getPlatformAuthority());
    }

    @Test
    void revokePlatformAuthority_success() {
        AccountId id = AccountId.generate();
        Account account = Account.reconstitute(id, new Email("admin@example.com"), new PasswordHash("hash"),
                AccountStatus.ACTIVE, null, PlatformAuthority.ADMINISTRATOR);
        assertEquals(PlatformAuthority.ADMINISTRATOR, account.getPlatformAuthority());

        account.revokePlatformAuthority();

        assertNull(account.getPlatformAuthority());
    }

    @Test
    void revokePlatformAuthority_notHeld_throwsNotHeldException() {
        Account account = Account.createAccount(new Email("user@example.com"), new PasswordHash("hash"));
        assertNull(account.getPlatformAuthority());

        assertThrows(PlatformAuthorityNotHeldException.class, account::revokePlatformAuthority);
        assertNull(account.getPlatformAuthority());
    }

    // --- ADR-050 D4: credentialsNotBefore ---

    @Test
    void createAccount_hasNoCredentialsNotBefore() {
        Account account = Account.createAccount(new Email("test@example.com"), new PasswordHash("hash"));

        assertNull(account.getCredentialsNotBefore());
    }

    @Test
    void changeCredentials_firstChange_setsTruncatedSecondPlusOne() {
        Account account = Account.createAccount(new Email("test@example.com"), new PasswordHash("old-hash"));

        account.changeCredentials(new PasswordHash("new-hash"), NOW);

        assertEquals(Instant.parse("2026-10-24T12:00:01Z"), account.getCredentialsNotBefore());
    }

    @Test
    void changeCredentials_twiceInSameSecond_growsOnEachChange() {
        Account account = Account.createAccount(new Email("test@example.com"), new PasswordHash("old-hash"));

        account.changeCredentials(new PasswordHash("hash-1"), NOW);
        account.changeCredentials(new PasswordHash("hash-2"), NOW.plusMillis(100));

        assertEquals(Instant.parse("2026-10-24T12:00:02Z"), account.getCredentialsNotBefore());
    }

    @Test
    void changeCredentials_whenInactive_doesNotTouchCredentialsNotBefore() {
        Account account = Account.reconstitute(AccountId.generate(), new Email("test@example.com"),
                new PasswordHash("hash"), AccountStatus.INACTIVE, null, null, null);

        assertThrows(InactiveAccountException.class,
                () -> account.changeCredentials(new PasswordHash("new-hash"), NOW));
        assertNull(account.getCredentialsNotBefore());
    }

    @Test
    void deactivate_whenStateChanges_setsCredentialsNotBefore() {
        Account account = Account.createAccount(new Email("test@example.com"), new PasswordHash("hash"));

        assertTrue(account.deactivate(NOW));

        assertEquals(Instant.parse("2026-10-24T12:00:01Z"), account.getCredentialsNotBefore());
    }

    @Test
    void deactivate_whenAlreadyInactive_doesNotTouchCredentialsNotBefore() {
        Instant previous = Instant.parse("2026-10-20T08:00:00Z");
        Account account = Account.reconstitute(AccountId.generate(), new Email("test@example.com"),
                new PasswordHash("hash"), AccountStatus.INACTIVE, null, null, previous);

        assertFalse(account.deactivate(NOW));

        assertEquals(previous, account.getCredentialsNotBefore());
    }

    @Test
    void deactivate_withPlatformAuthority_doesNotTouchCredentialsNotBefore() {
        Account account = Account.reconstitute(AccountId.generate(), new Email("admin@example.com"),
                new PasswordHash("hash"), AccountStatus.ACTIVE, null, PlatformAuthority.ADMINISTRATOR, null);

        assertThrows(PlatformAdministratorDeactivationException.class, () -> account.deactivate(NOW));
        assertNull(account.getCredentialsNotBefore());
    }

    @Test
    void reactivate_keepsCredentialsNotBefore() {
        Account account = Account.createAccount(new Email("test@example.com"), new PasswordHash("hash"));
        account.deactivate(NOW);

        account.reactivate();

        assertEquals(Instant.parse("2026-10-24T12:00:01Z"), account.getCredentialsNotBefore());
    }

    @Test
    void acceptsTokenIssuedAt_withoutCredentialsNotBefore_acceptsAnyIat() {
        Account account = Account.createAccount(new Email("test@example.com"), new PasswordHash("hash"));

        assertTrue(account.acceptsTokenIssuedAt(Instant.parse("2000-01-01T00:00:00Z")));
    }

    @Test
    void acceptsTokenIssuedAt_atTheBorders() {
        Instant cnb = Instant.parse("2026-10-24T12:00:01Z");
        Account account = Account.reconstitute(AccountId.generate(), new Email("test@example.com"),
                new PasswordHash("hash"), AccountStatus.ACTIVE, null, null, cnb);

        assertTrue(account.acceptsTokenIssuedAt(cnb));
        assertTrue(account.acceptsTokenIssuedAt(cnb.plusSeconds(1)));
        assertFalse(account.acceptsTokenIssuedAt(cnb.minusSeconds(1)));
    }

    @Test
    void acceptsTokenIssuedAt_tokenFromTheSameSecondAsTheChange_isRejected() {
        Account account = Account.createAccount(new Email("test@example.com"), new PasswordHash("hash"));

        account.changeCredentials(new PasswordHash("new-hash"), NOW);

        assertFalse(account.acceptsTokenIssuedAt(Instant.parse("2026-10-24T12:00:00Z")));
    }

    @Test
    void reconstitute_withSevenArguments_keepsCredentialsNotBefore() {
        Instant cnb = Instant.parse("2026-10-24T12:00:01Z");
        Account account = Account.reconstitute(AccountId.generate(), new Email("test@example.com"),
                new PasswordHash("hash"), AccountStatus.ACTIVE, null, null, cnb);

        assertEquals(cnb, account.getCredentialsNotBefore());
    }

    @Test
    void reconstitute_withSixArguments_hasNoCredentialsNotBefore() {
        Account account = Account.reconstitute(AccountId.generate(), new Email("test@example.com"),
                new PasswordHash("hash"), AccountStatus.ACTIVE, null, null);

        assertNull(account.getCredentialsNotBefore());
    }
}
