package identity.domain.model;

import identity.domain.exception.AccountAlreadyBelongsToOrganizationException;
import identity.domain.exception.AccountNotMemberOfOrganizationException;
import identity.domain.exception.AccountNotRepresentativeException;
import identity.domain.exception.CannotRemoveLastRoleException;
import identity.domain.exception.RepresentativeTransferRequiredException;
import identity.domain.exception.SelfTransferNotAllowedException;
import identity.domain.exception.TransferTargetNotMemberException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OrganizationTest {

    @Test
    void testCreateOrganization() {
        AccountId repId = AccountId.generate();
        Organization org = Organization.createOrganization(OrganizationType.FOUNDATION, repId);

        assertNotNull(org.getOrganizationId());
        assertEquals(OrganizationType.FOUNDATION, org.getType());
        assertEquals(1, org.getMembers().size());

        Membership membership = org.getMembers().get(0);
        assertEquals(repId, membership.getAccountId());
        assertTrue(membership.hasRole(Role.REPRESENTATIVE));
    }

    @Test
    void testCreateOrganization_WithNulls_ThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> Organization.createOrganization(null, AccountId.generate()));
        assertThrows(IllegalArgumentException.class, () -> Organization.createOrganization(OrganizationType.COMPANY, null));
    }

    @Test
    void testAddEmployee() {
        Organization org = Organization.createOrganization(OrganizationType.COMPANY, AccountId.generate());
        AccountId employeeId = AccountId.generate();

        org.addEmployee(employeeId);

        assertEquals(2, org.getMembers().size());
        Membership membership = org.getMembers().stream()
                .filter(m -> m.getAccountId().equals(employeeId))
                .findFirst()
                .orElseThrow();
        
        assertTrue(membership.hasRole(Role.EMPLOYEE));
    }

    @Test
    void testAddEmployee_WhenAlreadyMember_ThrowsException() {
        AccountId repId = AccountId.generate();
        Organization org = Organization.createOrganization(OrganizationType.COMPANY, repId);

        assertThrows(AccountAlreadyBelongsToOrganizationException.class, () -> org.addEmployee(repId));
    }

    @Test
    void testAssignAdministrator() {
        AccountId repId = AccountId.generate();
        Organization org = Organization.createOrganization(OrganizationType.COMPANY, repId);
        AccountId employeeId = AccountId.generate();
        org.addEmployee(employeeId);

        boolean mutated = org.assignAdministrator(employeeId);
        assertTrue(mutated, "Should return true on actual mutation");

        Membership membership = org.getMembers().stream()
                .filter(m -> m.getAccountId().equals(employeeId))
                .findFirst()
                .orElseThrow();
        
        assertTrue(membership.hasRole(Role.ADMINISTRATOR));
        assertTrue(membership.hasRole(Role.EMPLOYEE));
    }

    @Test
    void testAssignAdministrator_WhenAlreadyAdministrator_ReturnsFalse() {
        AccountId repId = AccountId.generate();
        Organization org = Organization.createOrganization(OrganizationType.COMPANY, repId);
        AccountId employeeId = AccountId.generate();
        org.addEmployee(employeeId);
        
        org.assignAdministrator(employeeId); // First assignment

        boolean mutated = org.assignAdministrator(employeeId); // Idempotent assignment
        assertFalse(mutated, "Should return false on no-op mutation");
    }

    @Test
    void testAssignAdministrator_WhenNotMember_ThrowsException() {
        Organization org = Organization.createOrganization(OrganizationType.COMPANY, AccountId.generate());
        AccountId nonMemberId = AccountId.generate();

        assertThrows(AccountNotMemberOfOrganizationException.class, () -> org.assignAdministrator(nonMemberId));
    }

    @Test
    void testRemoveAdministrator() {
        Organization org = Organization.createOrganization(OrganizationType.COMPANY, AccountId.generate());
        AccountId employeeId = AccountId.generate();
        org.addEmployee(employeeId);
        org.assignAdministrator(employeeId);

        boolean mutated = org.removeAdministrator(employeeId);
        assertTrue(mutated, "Should return true on actual removal");

        Membership membership = org.getMembers().stream()
                .filter(m -> m.getAccountId().equals(employeeId))
                .findFirst()
                .orElseThrow();
        
        assertFalse(membership.hasRole(Role.ADMINISTRATOR));
        assertTrue(membership.hasRole(Role.EMPLOYEE));
    }

    @Test
    void testRemoveAdministrator_WhenNotAdministrator_ReturnsFalse() {
        Organization org = Organization.createOrganization(OrganizationType.COMPANY, AccountId.generate());
        AccountId employeeId = AccountId.generate();
        org.addEmployee(employeeId);

        boolean mutated = org.removeAdministrator(employeeId);
        assertFalse(mutated, "Should return false on no-op removal");
    }

    @Test
    void testRemoveAdministrator_WhenNotMember_ThrowsException() {
        Organization org = Organization.createOrganization(OrganizationType.COMPANY, AccountId.generate());
        AccountId nonMemberId = AccountId.generate();

        assertThrows(AccountNotMemberOfOrganizationException.class, () -> org.removeAdministrator(nonMemberId));
    }

    @Test
    void testRemoveAdministrator_WhenLastRole_ThrowsException() {
        Organization org = Organization.createOrganization(OrganizationType.COMPANY, AccountId.generate());
        AccountId employeeId = AccountId.generate();
        org.addEmployee(employeeId);
        org.assignAdministrator(employeeId);
        org.removeEmployee(employeeId); // Now they only have ADMINISTRATOR

        assertThrows(CannotRemoveLastRoleException.class, () -> org.removeAdministrator(employeeId));
    }

    @Test
    void testRemoveEmployee() {
        Organization org = Organization.createOrganization(OrganizationType.COMPANY, AccountId.generate());
        AccountId employeeId = AccountId.generate();
        org.addEmployee(employeeId);
        org.assignAdministrator(employeeId);

        boolean mutated = org.removeEmployee(employeeId);
        assertTrue(mutated, "Should return true on actual removal");

        Membership membership = org.getMembers().stream()
                .filter(m -> m.getAccountId().equals(employeeId))
                .findFirst()
                .orElseThrow();
        assertFalse(membership.hasRole(Role.EMPLOYEE));
        assertTrue(membership.hasRole(Role.ADMINISTRATOR));
    }

    @Test
    void testRemoveMemberFromOrganization() {
        Organization org = Organization.createOrganization(OrganizationType.COMPANY, AccountId.generate());
        AccountId employeeId = AccountId.generate();
        org.addEmployee(employeeId);

        org.removeMemberFromOrganization(employeeId);

        assertEquals(1, org.getMembers().size()); // Only representative left
        assertFalse(org.getMembers().stream().anyMatch(m -> m.getAccountId().equals(employeeId)));
    }

    @Test
    void testRemoveMemberFromOrganization_WhenRepresentative_ThrowsException() {
        AccountId repId = AccountId.generate();
        Organization org = Organization.createOrganization(OrganizationType.COMPANY, repId);

        assertThrows(RepresentativeTransferRequiredException.class, () -> org.removeMemberFromOrganization(repId));
    }

    @Test
    void testTransferRepresentativeAndRemove_WhenOnlyRepresentative_RemovesMembership() {
        AccountId currentRepId = AccountId.generate();
        Organization org = Organization.createOrganization(OrganizationType.COMPANY, currentRepId);
        AccountId employeeId = AccountId.generate();
        org.addEmployee(employeeId);

        org.transferRepresentativeAndRemove(currentRepId, employeeId);

        assertEquals(1, org.getMembers().size()); // currentRepId is completely removed

        Membership newRepMembership = org.getMembers().get(0);
        assertEquals(employeeId, newRepMembership.getAccountId());
        assertTrue(newRepMembership.hasRole(Role.REPRESENTATIVE));
        assertTrue(newRepMembership.hasRole(Role.EMPLOYEE));
    }

    @Test
    void testTransferRepresentativeAndRemove_WhenHasOtherRoles_KeepsMembership() {
        AccountId currentRepId = AccountId.generate();
        Organization org = Organization.createOrganization(OrganizationType.COMPANY, currentRepId);
        // Add ADMINISTRATOR so they have two roles
        org.assignAdministrator(currentRepId);

        AccountId employeeId = AccountId.generate();
        org.addEmployee(employeeId);

        org.transferRepresentativeAndRemove(currentRepId, employeeId);

        assertEquals(2, org.getMembers().size()); // currentRepId is kept
        
        Membership oldRepMembership = org.getMembers().stream()
                .filter(m -> m.getAccountId().equals(currentRepId))
                .findFirst()
                .orElseThrow();
        assertFalse(oldRepMembership.hasRole(Role.REPRESENTATIVE));
        assertTrue(oldRepMembership.hasRole(Role.ADMINISTRATOR));

        Membership newRepMembership = org.getMembers().stream()
                .filter(m -> m.getAccountId().equals(employeeId))
                .findFirst()
                .orElseThrow();
        assertTrue(newRepMembership.hasRole(Role.REPRESENTATIVE));
    }

    @Test
    void testTransferRepresentativeAndRemove_WhenSameAccount_ThrowsException() {
        AccountId currentRepId = AccountId.generate();
        Organization org = Organization.createOrganization(OrganizationType.COMPANY, currentRepId);

        assertThrows(SelfTransferNotAllowedException.class, () -> org.transferRepresentativeAndRemove(currentRepId, currentRepId));
    }

    @Test
    void testTransferRepresentativeAndRemove_WhenCurrentRepNotRepresentative_ThrowsException() {
        AccountId currentRepId = AccountId.generate();
        Organization org = Organization.createOrganization(OrganizationType.COMPANY, currentRepId);
        AccountId employeeId = AccountId.generate();
        org.addEmployee(employeeId);
        AccountId newRepId = AccountId.generate();
        org.addEmployee(newRepId);

        // employeeId is a member, but NOT the representative
        assertThrows(AccountNotRepresentativeException.class, () -> org.transferRepresentativeAndRemove(employeeId, newRepId));
    }

    @Test
    void testTransferRepresentativeAndRemove_WhenNewRepNotMember_ThrowsException() {
        AccountId currentRepId = AccountId.generate();
        Organization org = Organization.createOrganization(OrganizationType.COMPANY, currentRepId);
        AccountId newRepId = AccountId.generate(); // Not added

        assertThrows(TransferTargetNotMemberException.class, () -> org.transferRepresentativeAndRemove(currentRepId, newRepId));
    }
}
