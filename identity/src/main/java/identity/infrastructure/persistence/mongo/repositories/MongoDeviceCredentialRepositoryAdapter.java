package identity.infrastructure.persistence.mongo.repositories;

import identity.application.port.out.DeviceCredentialRepositoryPort;
import identity.domain.model.AccountId;
import identity.domain.model.DeviceCredential;
import identity.infrastructure.persistence.mongo.documents.DeviceCredentialDocument;
import identity.infrastructure.persistence.mongo.repositories.spring.SpringDataDeviceCredentialRepository;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

/** Adaptador Mongo de {@link DeviceCredentialRepositoryPort}. */
@Repository
public class MongoDeviceCredentialRepositoryAdapter implements DeviceCredentialRepositoryPort {

    private final SpringDataDeviceCredentialRepository repository;
    private final MongoTemplate mongoTemplate;

    public MongoDeviceCredentialRepositoryAdapter(SpringDataDeviceCredentialRepository repository,
                                                  MongoTemplate mongoTemplate) {
        this.repository = repository;
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void insert(DeviceCredential credential) {
        repository.insert(toDocument(credential));
    }

    @Override
    public Optional<DeviceCredential> findActiveByTokenHash(String tokenHash) {
        Query query = Query.query(Criteria.where("tokenHash").is(tokenHash).and("revokedAt").is(null));
        return Optional.ofNullable(mongoTemplate.findOne(query, DeviceCredentialDocument.class))
                .map(MongoDeviceCredentialRepositoryAdapter::toDomain);
    }

    @Override
    public long countActive(AccountId accountId) {
        return mongoTemplate.count(activeOf(accountId), DeviceCredentialDocument.class);
    }

    @Override
    public void markUsed(String credentialId, Instant usedAt) {
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(credentialId)),
                new Update().set("lastUsedAt", usedAt), DeviceCredentialDocument.class);
    }

    @Override
    public boolean revokeIfActive(String credentialId, AccountId accountId, Instant revokedAt) {
        Query query = Query.query(Criteria.where("_id").is(credentialId).and("accountId").is(accountId.value())
                .and("revokedAt").is(null));
        return mongoTemplate.updateFirst(query, new Update().set("revokedAt", revokedAt), DeviceCredentialDocument.class)
                .getModifiedCount() == 1;
    }

    @Override
    public long revokeAllActive(AccountId accountId, Instant revokedAt) {
        return mongoTemplate.updateMulti(activeOf(accountId), new Update().set("revokedAt", revokedAt),
                DeviceCredentialDocument.class).getModifiedCount();
    }

    private static Query activeOf(AccountId accountId) {
        return Query.query(Criteria.where("accountId").is(accountId.value()).and("revokedAt").is(null));
    }

    private static DeviceCredentialDocument toDocument(DeviceCredential c) {
        DeviceCredentialDocument d = new DeviceCredentialDocument();
        d.setCredentialId(c.getCredentialId());
        d.setAccountId(c.getAccountId().value());
        d.setTokenHash(c.getTokenHash());
        d.setDeviceName(c.getDeviceName());
        d.setCreatedAt(c.getCreatedAt());
        d.setLastUsedAt(c.getLastUsedAt());
        d.setRevokedAt(c.getRevokedAt());
        return d;
    }

    private static DeviceCredential toDomain(DeviceCredentialDocument d) {
        return DeviceCredential.reconstitute(d.getCredentialId(), new AccountId(d.getAccountId()), d.getTokenHash(),
                d.getDeviceName(), d.getCreatedAt(), d.getLastUsedAt(), d.getRevokedAt());
    }
}
