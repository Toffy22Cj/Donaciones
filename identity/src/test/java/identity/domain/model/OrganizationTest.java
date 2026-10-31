package identity.domain.model;

import identity.domain.exception.AccountAlreadyBelongsToOrganizationException;
import identity.domain.exception.AccountNotMemberOfOrganizationException;
import identity.domain.exception.AccountNotRepresentativeException;
import identity.domain.exception.CannotRemoveLastRoleException;
import identity.domain.exception.InvalidVerificationTransitionException;
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
        assertEquals(VerificationStatus.PENDING_VERIFICATION, org.getVerificationStatus());
        assertNull(org.getVerificationInformationRequest());
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

    @Test
    void testTransferRepresentativeAndRemove_WhenCurrentRepNotMember_ThrowsException() {
        AccountId currentRepId = AccountId.generate();
        Organization org = Organization.createOrganization(OrganizationType.COMPANY, currentRepId);
        AccountId nonMemberId = AccountId.generate(); // Not added
        AccountId newRepId = AccountId.generate();
        org.addEmployee(newRepId);

        // nonMemberId is completely unknown to the organization
        assertThrows(AccountNotMemberOfOrganizationException.class, () -> org.transferRepresentativeAndRemove(nonMemberId, newRepId));
    }

    @Test
    void testReconstitute() {
        OrganizationId orgId = OrganizationId.generate();
        AccountId repId = AccountId.generate();
        AccountId employeeId = AccountId.generate();
        
        Membership repMembership = new Membership(repId, java.util.Set.of(Role.REPRESENTATIVE, Role.ADMINISTRATOR));
        Membership empMembership = new Membership(employeeId, java.util.Set.of(Role.EMPLOYEE));
        
        java.util.List<Membership> members = java.util.List.of(repMembership, empMembership);

        Organization organization = Organization.reconstitute(orgId, OrganizationType.FOUNDATION, members, VerificationStatus.PENDING_VERIFICATION, null);

        assertEquals(orgId, organization.getOrganizationId());
        assertEquals(OrganizationType.FOUNDATION, organization.getType());
        assertEquals(2, organization.getMembers().size());
        assertEquals(VerificationStatus.PENDING_VERIFICATION, organization.getVerificationStatus());
        assertNull(organization.getVerificationInformationRequest());
        
        assertTrue(organization.getMembers().contains(repMembership));
        assertTrue(organization.getMembers().contains(empMembership));
    }

    @Test
    void transition_fromPendingVerification_toVerified_viaVerify() {
        Organization organization = Organization.createOrganization(OrganizationType.COMPANY, AccountId.generate());
        organization.applyProfileAtCreation(verificationData()); // ficha R-7, P2 (b)
        organization.verify();

        assertEquals(VerificationStatus.VERIFIED, organization.getVerificationStatus());
        assertNull(organization.getVerificationInformationRequest());
    }

    @Test
    void transition_fromPendingVerification_toRejected_viaReject() {
        Organization organization = Organization.createOrganization(OrganizationType.COMPANY, AccountId.generate());
        organization.reject();

        assertEquals(VerificationStatus.REJECTED, organization.getVerificationStatus());
        assertNull(organization.getVerificationInformationRequest());
    }

    @Test
    void transition_fromPendingVerification_toNeedsMoreInformation_viaRequestInformation() {
        Organization organization = Organization.createOrganization(OrganizationType.COMPANY, AccountId.generate());
        InformationRequestMessage message = new InformationRequestMessage("Falta RUT");
        organization.requestInformation(message);

        assertEquals(VerificationStatus.NEEDS_MORE_INFORMATION, organization.getVerificationStatus());
        assertEquals(message, organization.getVerificationInformationRequest());
    }

    @Test
    void transition_fromNeedsMoreInformation_toVerified_viaVerify_clearsMessage() {
        Organization organization = Organization.createOrganization(OrganizationType.COMPANY, AccountId.generate());
        organization.applyProfileAtCreation(verificationData()); // ficha R-7, P2 (b)
        organization.requestInformation(new InformationRequestMessage("Falta RUT"));
        organization.verify();

        assertEquals(VerificationStatus.VERIFIED, organization.getVerificationStatus());
        assertNull(organization.getVerificationInformationRequest());
    }

    @Test
    void transition_fromNeedsMoreInformation_toRejected_viaReject_clearsMessage() {
        Organization organization = Organization.createOrganization(OrganizationType.COMPANY, AccountId.generate());
        organization.requestInformation(new InformationRequestMessage("Falta RUT"));
        organization.reject();

        assertEquals(VerificationStatus.REJECTED, organization.getVerificationStatus());
        assertNull(organization.getVerificationInformationRequest());
    }

    @Test
    void transition_fromNeedsMoreInformation_selfTransition_replacesMessage() {
        Organization organization = Organization.createOrganization(OrganizationType.COMPANY, AccountId.generate());
        InformationRequestMessage firstMessage = new InformationRequestMessage("Primer requerimiento");
        InformationRequestMessage secondMessage = new InformationRequestMessage("Segundo requerimiento");

        organization.requestInformation(firstMessage);
        assertEquals(firstMessage, organization.getVerificationInformationRequest());

        organization.requestInformation(secondMessage);
        assertEquals(VerificationStatus.NEEDS_MORE_INFORMATION, organization.getVerificationStatus());
        assertEquals(secondMessage, organization.getVerificationInformationRequest());
    }

    @Test
    void invalidTransition_fromVerified_onVerify_throwsExceptionAndStateIntact() {
        Organization organization = Organization.createOrganization(OrganizationType.COMPANY, AccountId.generate());
        organization.applyProfileAtCreation(verificationData()); // ficha R-7, P2 (b)
        organization.verify();

        InvalidVerificationTransitionException ex = assertThrows(
                InvalidVerificationTransitionException.class,
                organization::verify
        );
        assertEquals(VerificationStatus.VERIFIED, ex.getCurrentStatus());
        assertEquals(VerificationCommand.VERIFY, ex.getCommand());
        assertEquals(VerificationStatus.VERIFIED, organization.getVerificationStatus());
        assertNull(organization.getVerificationInformationRequest());
    }

    @Test
    void invalidTransition_fromVerified_onReject_throwsExceptionAndStateIntact() {
        Organization organization = Organization.createOrganization(OrganizationType.COMPANY, AccountId.generate());
        organization.applyProfileAtCreation(verificationData()); // ficha R-7, P2 (b)
        organization.verify();

        InvalidVerificationTransitionException ex = assertThrows(
                InvalidVerificationTransitionException.class,
                organization::reject
        );
        assertEquals(VerificationStatus.VERIFIED, ex.getCurrentStatus());
        assertEquals(VerificationCommand.REJECT, ex.getCommand());
        assertEquals(VerificationStatus.VERIFIED, organization.getVerificationStatus());
        assertNull(organization.getVerificationInformationRequest());
    }

    @Test
    void invalidTransition_fromVerified_onRequestInformation_throwsExceptionAndStateIntact() {
        Organization organization = Organization.createOrganization(OrganizationType.COMPANY, AccountId.generate());
        organization.applyProfileAtCreation(verificationData()); // ficha R-7, P2 (b)
        organization.verify();

        InformationRequestMessage message = new InformationRequestMessage("Falta poder notarial");
        InvalidVerificationTransitionException ex = assertThrows(
                InvalidVerificationTransitionException.class,
                () -> organization.requestInformation(message)
        );
        assertEquals(VerificationStatus.VERIFIED, ex.getCurrentStatus());
        assertEquals(VerificationCommand.REQUEST_INFORMATION, ex.getCommand());
        assertEquals(VerificationStatus.VERIFIED, organization.getVerificationStatus());
        assertNull(organization.getVerificationInformationRequest());
    }

    @Test
    void invalidTransition_fromRejected_onVerify_throwsExceptionAndStateIntact() {
        Organization organization = Organization.createOrganization(OrganizationType.COMPANY, AccountId.generate());
        organization.reject();

        InvalidVerificationTransitionException ex = assertThrows(
                InvalidVerificationTransitionException.class,
                organization::verify
        );
        assertEquals(VerificationStatus.REJECTED, ex.getCurrentStatus());
        assertEquals(VerificationCommand.VERIFY, ex.getCommand());
        assertEquals(VerificationStatus.REJECTED, organization.getVerificationStatus());
        assertNull(organization.getVerificationInformationRequest());
    }

    @Test
    void invalidTransition_fromRejected_onReject_throwsExceptionAndStateIntact() {
        Organization organization = Organization.createOrganization(OrganizationType.COMPANY, AccountId.generate());
        organization.reject();

        InvalidVerificationTransitionException ex = assertThrows(
                InvalidVerificationTransitionException.class,
                organization::reject
        );
        assertEquals(VerificationStatus.REJECTED, ex.getCurrentStatus());
        assertEquals(VerificationCommand.REJECT, ex.getCommand());
        assertEquals(VerificationStatus.REJECTED, organization.getVerificationStatus());
        assertNull(organization.getVerificationInformationRequest());
    }

    @Test
    void invalidTransition_fromRejected_onRequestInformation_throwsExceptionAndStateIntact() {
        Organization organization = Organization.createOrganization(OrganizationType.COMPANY, AccountId.generate());
        organization.reject();

        InformationRequestMessage message = new InformationRequestMessage("Falta poder notarial");
        InvalidVerificationTransitionException ex = assertThrows(
                InvalidVerificationTransitionException.class,
                () -> organization.requestInformation(message)
        );
        assertEquals(VerificationStatus.REJECTED, ex.getCurrentStatus());
        assertEquals(VerificationCommand.REQUEST_INFORMATION, ex.getCommand());
        assertEquals(VerificationStatus.REJECTED, organization.getVerificationStatus());
        assertNull(organization.getVerificationInformationRequest());
    }

    @Test
    void requestInformation_withNullMessage_throwsNullPointerException() {
        Organization organization = Organization.createOrganization(OrganizationType.COMPANY, AccountId.generate());
        assertThrows(NullPointerException.class, () -> organization.requestInformation(null));
        assertEquals(VerificationStatus.PENDING_VERIFICATION, organization.getVerificationStatus());
    }

    @Test
    void reconstitute_withNullStatus_throwsNullPointerException() {
        OrganizationId orgId = OrganizationId.generate();
        java.util.List<Membership> members = java.util.List.of(new Membership(AccountId.generate(), java.util.Set.of(Role.REPRESENTATIVE)));
        assertThrows(NullPointerException.class, () ->
                Organization.reconstitute(orgId, OrganizationType.FOUNDATION, members, null, null)
        );
    }

    @Test
    void reconstitute_withInvalidStatusMessagePair_throwsIllegalArgumentException() {
        OrganizationId orgId = OrganizationId.generate();
        java.util.List<Membership> members = java.util.List.of(new Membership(AccountId.generate(), java.util.Set.of(Role.REPRESENTATIVE)));
        InformationRequestMessage msg = new InformationRequestMessage("Mensaje");

        // NEEDS_MORE_INFORMATION without message
        assertThrows(IllegalArgumentException.class, () ->
                Organization.reconstitute(orgId, OrganizationType.FOUNDATION, members, VerificationStatus.NEEDS_MORE_INFORMATION, null)
        );

        // PENDING_VERIFICATION with message
        assertThrows(IllegalArgumentException.class, () ->
                Organization.reconstitute(orgId, OrganizationType.FOUNDATION, members, VerificationStatus.PENDING_VERIFICATION, msg)
        );

        // VERIFIED with message
        assertThrows(IllegalArgumentException.class, () ->
                Organization.reconstitute(orgId, OrganizationType.FOUNDATION, members, VerificationStatus.VERIFIED, msg)
        );

        // REJECTED with message
        assertThrows(IllegalArgumentException.class, () ->
                Organization.reconstitute(orgId, OrganizationType.FOUNDATION, members, VerificationStatus.REJECTED, msg)
        );
    }

    // --- Ficha R-7 (§9, tests 2 a 4) ---

    /** Razón social y NIT sintético (P-R7-5), lo que P2 (b) exige para verificar. */
    static OrganizationProfile verificationData() {
        return OrganizationProfile.from(new OrganizationProfile.Draft(null, null, null, null, null,
                "Fundación Ejemplo", "900000006", "9", null, null));
    }

    private static Organization withStatus(VerificationStatus status) {
        Organization o = Organization.createOrganization(OrganizationType.FOUNDATION, AccountId.generate(), "Fundación");
        o.updateProfile("Fundación", verificationData());
        switch (status) {
            case VERIFIED -> o.verify();
            case REJECTED -> o.reject();
            case NEEDS_MORE_INFORMATION -> o.requestInformation(new InformationRequestMessage("Falta el RUT"));
            default -> { }
        }
        return o;
    }

    @Test
    void r7_resubmit_fromNeedsMoreInformation_goesBackToPending_andClearsTheRequest() {
        Organization o = withStatus(VerificationStatus.NEEDS_MORE_INFORMATION);

        o.resubmitForVerification();

        assertEquals(VerificationStatus.PENDING_VERIFICATION, o.getVerificationStatus());
        assertNull(o.getVerificationInformationRequest());
    }

    @Test
    void r7_resubmit_fromTheOtherThreeStates_isNotAllowed() {
        for (VerificationStatus status : new VerificationStatus[] {VerificationStatus.PENDING_VERIFICATION,
                VerificationStatus.VERIFIED, VerificationStatus.REJECTED}) {
            Organization o = withStatus(status);
            assertThrows(identity.domain.exception.VerificationResubmitNotAllowedException.class,
                    o::resubmitForVerification, status.name());
            assertEquals(status, o.getVerificationStatus());
        }
    }

    @Test
    void r7_verify_withoutLegalNameOrNit_isVerificationDataMissing_andWithBothItVerifies() {
        Organization none = Organization.createOrganization(OrganizationType.FOUNDATION, AccountId.generate(), "F");
        assertThrows(identity.domain.exception.VerificationDataMissingException.class, none::verify);
        assertEquals(VerificationStatus.PENDING_VERIFICATION, none.getVerificationStatus());

        Organization onlyName = Organization.createOrganization(OrganizationType.FOUNDATION, AccountId.generate(), "F");
        onlyName.updateProfile("F", OrganizationProfile.from(new OrganizationProfile.Draft(null, null, null, null, null,
                "Fundación", null, null, null, null)));
        assertThrows(identity.domain.exception.VerificationDataMissingException.class, onlyName::verify);

        Organization onlyNit = Organization.createOrganization(OrganizationType.FOUNDATION, AccountId.generate(), "F");
        onlyNit.updateProfile("F", OrganizationProfile.from(new OrganizationProfile.Draft(null, null, null, null, null,
                null, "900000006", "9", null, null)));
        assertThrows(identity.domain.exception.VerificationDataMissingException.class, onlyNit::verify);

        Organization both = withStatus(VerificationStatus.PENDING_VERIFICATION);
        both.verify();
        assertEquals(VerificationStatus.VERIFIED, both.getVerificationStatus());
    }

    @Test
    void r7_aVerifiedOrganization_cannotChangeItsIdentity_butCanChangeThePublicFields() {
        Organization o = withStatus(VerificationStatus.VERIFIED);
        OrganizationProfile base = o.getProfile();

        assertThrows(identity.domain.exception.OrganizationProfileLockedException.class,
                () -> o.updateProfile("Otro nombre", base));
        assertThrows(identity.domain.exception.OrganizationProfileLockedException.class,
                () -> o.updateProfile("Fundación", OrganizationProfile.from(new OrganizationProfile.Draft(null, null, null,
                        null, null, "Otra razón", "900000006", "9", null, null))));
        assertThrows(identity.domain.exception.OrganizationProfileLockedException.class,
                () -> o.updateProfile("Fundación", OrganizationProfile.from(new OrganizationProfile.Draft(null, null, null,
                        null, null, "Fundación Ejemplo", "900000014", "8", null, null))));
        assertEquals(base, o.getProfile());

        java.util.List<String> changed = o.updateProfile("Fundación", OrganizationProfile.from(new OrganizationProfile.Draft(
                "Nueva descripción", "https://f.example.org", null, null, null, "Fundación Ejemplo", "900000006", "9",
                null, null)));
        assertEquals(java.util.List.of("description", "website"), changed);
    }

    @Test
    void r7_specialTaxRegime_onlyForAFoundation() {
        Organization company = Organization.createOrganization(OrganizationType.COMPANY, AccountId.generate(), "E");
        identity.domain.exception.InvalidOrganizationProfileFieldException e = assertThrows(
                identity.domain.exception.InvalidOrganizationProfileFieldException.class,
                () -> company.updateProfile("E", OrganizationProfile.from(new OrganizationProfile.Draft(null, null, null,
                        null, null, null, null, null, Boolean.TRUE, null))));
        assertEquals("specialTaxRegime", e.getField());
    }
}
