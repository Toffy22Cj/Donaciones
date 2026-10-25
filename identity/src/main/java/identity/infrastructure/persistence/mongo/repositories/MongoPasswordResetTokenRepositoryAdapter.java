package identity.infrastructure.persistence.mongo.repositories;

import identity.application.port.out.PasswordResetTokenRepositoryPort;
import identity.domain.model.AccountId;
import identity.domain.model.PasswordResetToken;
import identity.domain.model.PasswordResetTokenStatus;
import identity.infrastructure.persistence.mongo.documents.PasswordResetTokenDocument;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

/** Adaptador Mongo de {@link PasswordResetTokenRepositoryPort} (ADR-050 D8). */
@Repository
public class MongoPasswordResetTokenRepositoryAdapter implements PasswordResetTokenRepositoryPort {

    private static final String PENDING = PasswordResetTokenStatus.PENDING.name();

    private final MongoTemplate mongoTemplate;

    public MongoPasswordResetTokenRepositoryAdapter(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void insert(PasswordResetToken token) {
        mongoTemplate.insert(toDocument(token));
    }

    @Override
    public Optional<PasswordResetToken> findByTokenHash(String tokenHash) {
        return Optional.ofNullable(mongoTemplate.findOne(Query.query(Criteria.where("tokenHash").is(tokenHash)),
                PasswordResetTokenDocument.class)).map(MongoPasswordResetTokenRepositoryAdapter::toDomain);
    }

    @Override
    public Optional<PasswordResetToken> findPendingByAccount(AccountId accountId) {
        return Optional.ofNullable(mongoTemplate.findOne(Query.query(Criteria.where("accountId").is(accountId.value())
                .and("status").is(PENDING)), PasswordResetTokenDocument.class))
                .map(MongoPasswordResetTokenRepositoryAdapter::toDomain);
    }

    @Override
    public long supersedePendingFor(AccountId accountId) {
        Query query = Query.query(Criteria.where("accountId").is(accountId.value()).and("status").is(PENDING));
        return mongoTemplate.updateMulti(query, new Update().set("status", PasswordResetTokenStatus.SUPERSEDED.name()),
                PasswordResetTokenDocument.class).getModifiedCount();
    }

    @Override
    public boolean markUsedIfPending(String id, Instant usedAt) {
        Query query = Query.query(Criteria.where("_id").is(id).and("status").is(PENDING));
        Update update = new Update().set("status", PasswordResetTokenStatus.USED.name()).set("usedAt", usedAt);
        return mongoTemplate.updateFirst(query, update, PasswordResetTokenDocument.class).getModifiedCount() == 1;
    }

    private static PasswordResetTokenDocument toDocument(PasswordResetToken t) {
        PasswordResetTokenDocument d = new PasswordResetTokenDocument();
        d.setId(t.getId());
        d.setTokenHash(t.getTokenHash());
        d.setAccountId(t.getAccountId().value());
        d.setStatus(t.getStatus().name());
        d.setCreatedAt(t.getCreatedAt());
        d.setExpiresAt(t.getExpiresAt());
        d.setUsedAt(t.getUsedAt());
        return d;
    }

    private static PasswordResetToken toDomain(PasswordResetTokenDocument d) {
        return PasswordResetToken.reconstitute(d.getId(), d.getTokenHash(), new AccountId(d.getAccountId()),
                PasswordResetTokenStatus.valueOf(d.getStatus()), d.getCreatedAt(), d.getExpiresAt(), d.getUsedAt());
    }
}
