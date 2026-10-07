package identity.domain.model;

import identity.domain.exception.InvalidMemberRoleException;
import identity.domain.exception.MemberAlreadyHasRoleException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR-049 D2 y D7: token, invitación y cambio de rol. */
class OrganizationInvitationTest {

    static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");
    static final OrganizationId ORG = new OrganizationId("01J9ZORG000000000000000000");

    @Test
    void theToken_has256RandomBits_andItsHashIsSha256_andToStringNeverShowsIt() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            InvitationToken t = InvitationToken.generate();
            assertThat(Base64.getUrlDecoder().decode(t.value())).hasSize(32);
            assertThat(t.value()).matches("[A-Za-z0-9_-]{43}");
            assertThat(t.hash()).matches("[0-9a-f]{64}").isEqualTo(InvitationToken.hashOf(t.value()));
            assertThat(t.toString()).doesNotContain(t.value());
            assertThat(seen.add(t.value())).isTrue();
        }
        // vector conocido: SHA-256("abc")
        assertThat(InvitationToken.hashOf("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void anInvitation_storesOnlyTheHash_theLowercaseEmail_andExpiresInSevenDays() {
        InvitationToken token = InvitationToken.generate();
        OrganizationInvitation i = OrganizationInvitation.issue("inv-1", ORG, new Email("Ana.Perez@Example.ORG"),
                Role.EMPLOYEE, token, new AccountId("admin"), NOW);
        assertThat(i.getTokenHash()).isEqualTo(token.hash());
        assertThat(i.getEmail()).isEqualTo("ana.perez@example.org");
        assertThat(i.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
        assertThat(i.getStatus()).isEqualTo(InvitationStatus.PENDING);
        assertThat(i.getDelivery()).isEqualTo(InvitationDelivery.PENDING);
        assertThat(i.toString()).doesNotContain(token.value()).doesNotContain(token.hash()).doesNotContain("ana");
        assertThat(i.maskedEmail()).isEqualTo("a***@example.org");
        assertThat(i.isFor(new Email("ANA.PEREZ@example.org"))).isTrue();
        assertThat(i.isFor(new Email("ana.perez@example.com"))).isFalse();
        assertThat(i.isOpenAt(NOW.plus(Duration.ofDays(7)).minusMillis(1))).isTrue();
        assertThat(i.isOpenAt(NOW.plus(Duration.ofDays(7)))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"REPRESENTATIVE", "administrator", "OWNER", ""})
    void onlyAdministratorOrEmployee_canBeInvited(String role) {
        assertThatThrownBy(() -> OrganizationInvitation.invitableRole(role)).isInstanceOf(InvalidMemberRoleException.class);
        assertThatThrownBy(() -> OrganizationInvitation.issue("inv", ORG, new Email("a@b.org"), Role.REPRESENTATIVE,
                InvitationToken.generate(), null, NOW)).isInstanceOf(InvalidMemberRoleException.class);
    }

    @Test
    void changeMemberRole_promotesKeepingTheRest_demotesKeepingEmployee_andRejectsTheSameState() {
        AccountId rep = new AccountId("rep");
        AccountId member = new AccountId("member");
        Organization org = Organization.createOrganization(OrganizationType.FOUNDATION, rep, "Org");
        org.addEmployee(member);

        assertThatThrownBy(() -> org.changeMemberRole(member, Role.EMPLOYEE)).isInstanceOf(MemberAlreadyHasRoleException.class);
        org.changeMemberRole(member, Role.ADMINISTRATOR);
        assertThat(org.membershipOf(member).orElseThrow().getRoles()).containsExactlyInAnyOrder(Role.EMPLOYEE, Role.ADMINISTRATOR);
        assertThatThrownBy(() -> org.changeMemberRole(member, Role.ADMINISTRATOR)).isInstanceOf(MemberAlreadyHasRoleException.class);
        org.changeMemberRole(member, Role.EMPLOYEE);
        assertThat(org.membershipOf(member).orElseThrow().getRoles()).containsExactly(Role.EMPLOYEE);

        // el representante conserva REPRESENTATIVE; pasar a EMPLOYEE le añade EMPLOYEE y le quita ADMINISTRATOR
        org.changeMemberRole(rep, Role.ADMINISTRATOR);
        org.changeMemberRole(rep, Role.EMPLOYEE);
        assertThat(org.membershipOf(rep).orElseThrow().getRoles()).containsExactlyInAnyOrder(Role.REPRESENTATIVE, Role.EMPLOYEE);
        assertThatThrownBy(() -> org.changeMemberRole(rep, Role.REPRESENTATIVE)).isInstanceOf(InvalidMemberRoleException.class);
        assertThatThrownBy(() -> org.changeMemberRole(new AccountId("nobody"), Role.EMPLOYEE))
                .isInstanceOf(identity.domain.exception.AccountNotMemberOfOrganizationException.class);
    }
}
