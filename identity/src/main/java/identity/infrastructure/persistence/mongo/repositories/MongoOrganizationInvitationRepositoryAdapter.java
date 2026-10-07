package identity.infrastructure.persistence.mongo.repositories;

import identity.application.port.out.OrganizationInvitationRepositoryPort;
import identity.domain.model.AccountId;
import identity.domain.model.InvitationDelivery;
import identity.domain.model.InvitationStatus;
import identity.domain.model.OrganizationId;
import identity.domain.model.OrganizationInvitation;
import identity.domain.model.Role;
import identity.infrastructure.persistence.mongo.documents.OrganizationInvitationDocument;
import identity.infrastructure.persistence.mongo.repositories.spring.SpringDataOrganizationInvitationRepository;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Adaptador Mongo de {@link OrganizationInvitationRepositoryPort} (ADR-049 D2). */
@Repository
public class MongoOrganizationInvitationRepositoryAdapter implements OrganizationInvitationRepositoryPort {

    private final SpringDataOrganizationInvitationRepository repository;
    private final MongoTemplate mongoTemplate;

    public MongoOrganizationInvitationRepositoryAdapter(SpringDataOrganizationInvitationRepository repository,
                                                        MongoTemplate mongoTemplate) {
        this.repository = repository;
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void insert(OrganizationInvitation invitation) {
        repository.insert(toDocument(invitation));
    }

    @Override
    public Optional<OrganizationInvitation> findById(String invitationId) {
        return repository.findById(invitationId).map(MongoOrganizationInvitationRepositoryAdapter::toDomain);
    }

    @Override
    public Optional<OrganizationInvitation> findByTokenHash(String tokenHash) {
        return Optional.ofNullable(mongoTemplate.findOne(Query.query(Criteria.where("tokenHash").is(tokenHash)),
                OrganizationInvitationDocument.class)).map(MongoOrganizationInvitationRepositoryAdapter::toDomain);
    }

    @Override
    public List<OrganizationInvitation> findPendingByOrganization(OrganizationId organizationId, int limit) {
        Query query = Query.query(Criteria.where("organizationId").is(organizationId.value())
                .and("status").is(InvitationStatus.PENDING.name())).with(Sort.by("createdAt", "_id")).limit(limit);
        return mongoTemplate.find(query, OrganizationInvitationDocument.class).stream()
                .map(MongoOrganizationInvitationRepositoryAdapter::toDomain).toList();
    }

    @Override
    public long revokePendingFor(OrganizationId organizationId, String normalizedEmail, Instant revokedAt) {
        Query query = Query.query(Criteria.where("organizationId").is(organizationId.value())
                .and("email").is(normalizedEmail).and("status").is(InvitationStatus.PENDING.name()));
        Update update = new Update().set("status", InvitationStatus.REVOKED.name()).set("revokedAt", revokedAt);
        return mongoTemplate.updateMulti(query, update, OrganizationInvitationDocument.class).getModifiedCount();
    }

    @Override
    public boolean markRevokedIfPending(String invitationId, Instant revokedAt) {
        return transition(invitationId, new Update().set("status", InvitationStatus.REVOKED.name())
                .set("revokedAt", revokedAt));
    }

    @Override
    public boolean markAcceptedIfPending(String invitationId, AccountId acceptedBy, Instant acceptedAt) {
        return transition(invitationId, new Update().set("status", InvitationStatus.ACCEPTED.name())
                .set("acceptedBy", acceptedBy.value()).set("acceptedAt", acceptedAt));
    }

    @Override
    public void updateDelivery(String invitationId, InvitationDelivery delivery) {
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(invitationId)),
                new Update().set("delivery", delivery.name()), OrganizationInvitationDocument.class);
    }

    private boolean transition(String invitationId, Update update) {
        Query query = Query.query(Criteria.where("_id").is(invitationId).and("status").is(InvitationStatus.PENDING.name()));
        return mongoTemplate.updateFirst(query, update, OrganizationInvitationDocument.class).getModifiedCount() == 1;
    }

    private static OrganizationInvitationDocument toDocument(OrganizationInvitation i) {
        OrganizationInvitationDocument d = new OrganizationInvitationDocument();
        d.setInvitationId(i.getInvitationId());
        d.setOrganizationId(i.getOrganizationId().value());
        d.setEmail(i.getEmail());
        d.setRole(i.getRole().name());
        d.setTokenHash(i.getTokenHash());
        d.setInvitedBy(i.getInvitedBy() == null ? null : i.getInvitedBy().value());
        d.setCreatedAt(i.getCreatedAt());
        d.setExpiresAt(i.getExpiresAt());
        d.setStatus(i.getStatus().name());
        d.setDelivery(i.getDelivery().name());
        return d;
    }

    private static OrganizationInvitation toDomain(OrganizationInvitationDocument d) {
        return OrganizationInvitation.reconstitute(d.getInvitationId(), new OrganizationId(d.getOrganizationId()),
                d.getEmail(), Role.valueOf(d.getRole()), d.getTokenHash(),
                d.getInvitedBy() == null ? null : new AccountId(d.getInvitedBy()), d.getCreatedAt(), d.getExpiresAt(),
                InvitationStatus.valueOf(d.getStatus()), InvitationDelivery.valueOf(d.getDelivery()));
    }
}
