package identity.infrastructure.persistence.mongo.repositories;

import identity.application.port.out.DonorPseudonymRepositoryPort;
import identity.infrastructure.persistence.mongo.documents.DonorPseudonymDocument;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class MongoDonorPseudonymAdapter implements DonorPseudonymRepositoryPort {

    private final MongoTemplate mongoTemplate;

    public MongoDonorPseudonymAdapter(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public String findOrInsert(String accountId, String candidate) {
        Query byAccount = new Query(Criteria.where("_id").is(accountId));
        try {
            DonorPseudonymDocument doc = mongoTemplate.findAndModify(byAccount,
                    new Update().setOnInsert("pseudonym", candidate),
                    new FindAndModifyOptions().upsert(true).returnNew(true), DonorPseudonymDocument.class);
            return doc.getPseudonym();
        } catch (DuplicateKeyException e) {
            // dos upserts concurrentes del mismo _id: gana uno, el otro lee su resultado
            return mongoTemplate.findOne(byAccount, DonorPseudonymDocument.class).getPseudonym();
        }
    }

    @Override
    public Optional<String> find(String accountId) {
        return Optional.ofNullable(mongoTemplate.findById(accountId, DonorPseudonymDocument.class))
                .map(DonorPseudonymDocument::getPseudonym);
    }

    @Override
    public boolean delete(String accountId) {
        return mongoTemplate.remove(new Query(Criteria.where("_id").is(accountId)), DonorPseudonymDocument.class)
                .getDeletedCount() > 0;
    }
}
