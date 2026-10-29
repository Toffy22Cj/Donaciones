package identity.infrastructure.persistence.mongo.repositories;

import identity.application.port.out.EmailVerificationTokenRepositoryPort;
import identity.domain.model.AccountId;
import identity.domain.model.EmailVerificationToken;
import identity.domain.model.EmailVerificationTokenStatus;
import identity.infrastructure.persistence.mongo.documents.EmailVerificationTokenDocument;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

/** [Plan] Adaptador Mongo de {@link EmailVerificationTokenRepositoryPort} (ADR-050 Enmienda 2, E2-2). */
@Repository
public class MongoEmailVerificationTokenRepositoryAdapter implements EmailVerificationTokenRepositoryPort {

    private static final String PENDING = EmailVerificationTokenStatus.PENDING.name();

    private final MongoTemplate mongoTemplate;

    public MongoEmailVerificationTokenRepositoryAdapter(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void insert(EmailVerificationToken token) {
        mongoTemplate.insert(toDocument(token));
    }

    @Override
    public Optional<EmailVerificationToken> findByTokenHash(String tokenHash) {
        return Optional.ofNullable(mongoTemplate.findOne(Query.query(Criteria.where("tokenHash").is(tokenHash)),
                EmailVerificationTokenDocument.class)).map(MongoEmailVerificationTokenRepositoryAdapter::toDomain);
    }

    @Override
    public Optional<EmailVerificationToken> findPendingByAccount(AccountId accountId) {
        return Optional.ofNullable(mongoTemplate.findOne(Query.query(Criteria.where("accountId").is(accountId.value())
                .and("status").is(PENDING)), EmailVerificationTokenDocument.class))
                .map(MongoEmailVerificationTokenRepositoryAdapter::toDomain);
    }

    @Override
    public long supersedePendingFor(AccountId accountId) {
        Query query = Query.query(Criteria.where("accountId").is(accountId.value()).and("status").is(PENDING));
        return mongoTemplate.updateMulti(query, new Update().set("status", EmailVerificationTokenStatus.SUPERSEDED.name()),
                EmailVerificationTokenDocument.class).getModifiedCount();
    }

    @Override
    public boolean markUsedIfPending(String id, Instant usedAt) {
        Query query = Query.query(Criteria.where("_id").is(id).and("status").is(PENDING));
        Update update = new Update().set("status", EmailVerificationTokenStatus.USED.name()).set("usedAt", usedAt);
        return mongoTemplate.updateFirst(query, update, EmailVerificationTokenDocument.class).getModifiedCount() == 1;
    }

    private static EmailVerificationTokenDocument toDocument(EmailVerificationToken t) {
        EmailVerificationTokenDocument d = new EmailVerificationTokenDocument();
        d.setId(t.getId());
        d.setTokenHash(t.getTokenHash());
        d.setAccountId(t.getAccountId().value());
        d.setStatus(t.getStatus().name());
        d.setCreatedAt(t.getCreatedAt());
        d.setExpiresAt(t.getExpiresAt());
        d.setUsedAt(t.getUsedAt());
        return d;
    }

    private static EmailVerificationToken toDomain(EmailVerificationTokenDocument d) {
        return EmailVerificationToken.reconstitute(d.getId(), d.getTokenHash(), new AccountId(d.getAccountId()),
                EmailVerificationTokenStatus.valueOf(d.getStatus()), d.getCreatedAt(), d.getExpiresAt(), d.getUsedAt());
    }
}
