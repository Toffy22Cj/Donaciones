package identity.infrastructure.persistence.mongo;

import identity.domain.model.AccountId;
import identity.domain.model.Email;
import identity.domain.model.InvitationDelivery;
import identity.domain.model.InvitationStatus;
import identity.domain.model.InvitationToken;
import identity.domain.model.OrganizationId;
import identity.domain.model.OrganizationInvitation;
import identity.domain.model.Role;
import identity.infrastructure.persistence.mongo.repositories.MongoOrganizationInvitationRepositoryAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR-049 D2 y D4: las transiciones de la invitación son escrituras condicionales sobre {@code PENDING}. */
@DataMongoTest
@Import(MongoOrganizationInvitationRepositoryAdapter.class)
class OrganizationInvitationPersistenceIntegrationTest extends BaseMongoIntegrationTest {

    @Autowired private MongoOrganizationInvitationRepositoryAdapter invitations;
    @Autowired private MongoTemplate mongoTemplate;

    static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");
    static final OrganizationId ORG = new OrganizationId("01J9ZORG000000000000000001");

    private OrganizationInvitation issued(String email) {
        return OrganizationInvitation.issue(UUID.randomUUID().toString(), ORG, new Email(email), Role.EMPLOYEE,
                InvitationToken.generate(), new AccountId("admin"), NOW);
    }

    @Test
    void acceptingAndRevoking_onlyApplyToAPendingInvitation_once() {
        OrganizationInvitation i = issued("once@example.org");
        invitations.insert(i);

        assertThat(invitations.markAcceptedIfPending(i.getInvitationId(), new AccountId("a"), NOW)).isTrue();
        assertThat(invitations.markAcceptedIfPending(i.getInvitationId(), new AccountId("b"), NOW)).isFalse();
        assertThat(invitations.markRevokedIfPending(i.getInvitationId(), NOW)).isFalse();
        assertThat(invitations.findById(i.getInvitationId()).orElseThrow().getStatus()).isEqualTo(InvitationStatus.ACCEPTED);
        assertThat(mongoTemplate.getCollection("organization_invitations").find(
                new org.bson.Document("_id", i.getInvitationId())).first().getString("acceptedBy")).isEqualTo("a");

        OrganizationInvitation j = issued("revoked@example.org");
        invitations.insert(j);
        assertThat(invitations.markRevokedIfPending(j.getInvitationId(), NOW)).isTrue();
        assertThat(invitations.markAcceptedIfPending(j.getInvitationId(), new AccountId("a"), NOW)).isFalse();
    }

    @Test
    void reinvitingRevokesOnlyThePendingOnesOfThatEmail_andTheTokenHashIsUnique() {
        OrganizationInvitation first = issued("same@example.org");
        OrganizationInvitation other = issued("other@example.org");
        invitations.insert(first);
        invitations.insert(other);

        assertThat(invitations.revokePendingFor(ORG, "same@example.org", NOW)).isEqualTo(1);
        assertThat(invitations.findById(first.getInvitationId()).orElseThrow().getStatus()).isEqualTo(InvitationStatus.REVOKED);
        assertThat(invitations.findById(other.getInvitationId()).orElseThrow().getStatus()).isEqualTo(InvitationStatus.PENDING);
        assertThat(invitations.findByTokenHash(other.getTokenHash())).map(OrganizationInvitation::getInvitationId)
                .hasValue(other.getInvitationId());

        invitations.updateDelivery(other.getInvitationId(), InvitationDelivery.SENT);
        assertThat(invitations.findById(other.getInvitationId()).orElseThrow().getDelivery()).isEqualTo(InvitationDelivery.SENT);

        OrganizationInvitation clash = OrganizationInvitation.reconstitute(UUID.randomUUID().toString(), ORG,
                "x@example.org", Role.EMPLOYEE, other.getTokenHash(), null, NOW, NOW.plusSeconds(60),
                InvitationStatus.PENDING, InvitationDelivery.PENDING);
        assertThatThrownBy(() -> invitations.insert(clash)).isInstanceOf(DuplicateKeyException.class);
    }
}
