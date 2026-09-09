package identity.domain.model;

import identity.domain.exception.CannotRemoveLastRoleException;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MembershipTest {

    @Test
    void testCreateMembership() {
        AccountId accountId = AccountId.generate();
        Membership membership = new Membership(accountId, Set.of(Role.EMPLOYEE));

        assertEquals(accountId, membership.getAccountId());
        assertTrue(membership.hasRole(Role.EMPLOYEE));
        assertEquals(1, membership.getRoles().size());
    }

    @Test
    void testCreateMembership_WithNullAccountId_ThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> new Membership(null, Set.of(Role.EMPLOYEE)));
    }

    @Test
    void testCreateMembership_WithNullOrEmptyRoles_ThrowsException() {
        AccountId accountId = AccountId.generate();
        assertThrows(IllegalArgumentException.class, () -> new Membership(accountId, null));
        assertThrows(IllegalArgumentException.class, () -> new Membership(accountId, Set.of()));
    }

    @Test
    void testAddRole() {
        AccountId accountId = AccountId.generate();
        Membership membership = new Membership(accountId, Set.of(Role.EMPLOYEE));

        assertTrue(membership.addRole(Role.ADMINISTRATOR));
        assertTrue(membership.hasRole(Role.ADMINISTRATOR));
        assertEquals(2, membership.getRoles().size());

        // Idempotent add returns false
        assertFalse(membership.addRole(Role.ADMINISTRATOR));
    }

    @Test
    void testRemoveRole() {
        AccountId accountId = AccountId.generate();
        Membership membership = new Membership(accountId, Set.of(Role.EMPLOYEE, Role.ADMINISTRATOR));

        assertTrue(membership.removeRole(Role.ADMINISTRATOR));
        assertFalse(membership.hasRole(Role.ADMINISTRATOR));
        assertTrue(membership.hasRole(Role.EMPLOYEE));
        assertEquals(1, membership.getRoles().size());

        // Idempotent remove returns false
        assertFalse(membership.removeRole(Role.ADMINISTRATOR));
    }

    @Test
    void testRemoveRole_WhenLastRole_ThrowsException() {
        AccountId accountId = AccountId.generate();
        Membership membership = new Membership(accountId, Set.of(Role.EMPLOYEE));

        assertThrows(CannotRemoveLastRoleException.class, () -> membership.removeRole(Role.EMPLOYEE));
    }
}
