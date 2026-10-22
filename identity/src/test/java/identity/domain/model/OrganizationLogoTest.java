package identity.domain.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR-051 F9 y F10; plan de la Tarea 2 §4.1: la referencia del logo en {@link Organization}. */
class OrganizationLogoTest {

    private static Organization organization() {
        return Organization.createOrganization(OrganizationType.FOUNDATION, AccountId.generate(), "Fundación Logo");
    }

    @Test
    void aNewOrganization_hasNoLogo() {
        assertThat(organization().getLogoObjectKey()).isNull();
    }

    @Test
    void replacingTheLogo_returnsThePreviousReference() {
        Organization o = organization();

        assertThat(o.replaceLogo("01KEYA")).isEmpty();
        assertThat(o.replaceLogo("01KEYB")).contains("01KEYA");
        assertThat(o.getLogoObjectKey()).isEqualTo("01KEYB");
    }

    @Test
    void removingTheLogo_returnsThePreviousReference_andRemovingWithoutLogoReturnsNothing() {
        Organization o = organization();
        assertThat(o.removeLogo()).isEmpty();

        o.replaceLogo("01KEYA");
        assertThat(o.removeLogo()).contains("01KEYA");
        assertThat(o.getLogoObjectKey()).isNull();
    }

    @Test
    void theLogo_doesNotTouchMembersNorVerification() {
        Organization o = organization();
        List<Membership> members = List.copyOf(o.getMembers());
        VerificationStatus status = o.getVerificationStatus();

        o.replaceLogo("01KEYA");
        o.removeLogo();

        assertThat(o.getMembers()).isEqualTo(members);
        assertThat(o.getVerificationStatus()).isEqualTo(status);
        assertThat(o.getMembers().get(0).getRoles()).isEqualTo(Set.of(Role.REPRESENTATIVE));
    }

    @Test
    void reconstitute_keepsTheLogoReference() {
        Organization o = Organization.reconstitute(OrganizationId.generate(), OrganizationType.COMPANY,
                List.of(new Membership(AccountId.generate(), Set.of(Role.REPRESENTATIVE))),
                VerificationStatus.VERIFIED, null, "Empresa", "01KEYC");

        assertThat(o.getLogoObjectKey()).isEqualTo("01KEYC");
        assertThat(o.getName()).isEqualTo("Empresa");
    }
}
