package identity.infrastructure.persistence.mongo.repositories;

import identity.application.port.out.PlatformAuthorityStatePort;
import identity.domain.exception.PlatformAlreadyBootstrappedException;
import identity.domain.exception.PlatformAuthorityStateMissingException;
import identity.infrastructure.persistence.mongo.documents.PlatformAuthorityStateDocument;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/**
 * MongoDB adapter implementing {@link PlatformAuthorityStatePort} for the singleton document (ADR-038 §2.3).
 */
@Repository
@RequiredArgsConstructor
public class MongoPlatformAuthorityStateAdapter implements PlatformAuthorityStatePort {

    public static final String COLLECTION_NAME = "platform_authority_state";
    public static final String SINGLETON_ID = "platform-authority";

    private final MongoTemplate mongoTemplate;

    @Override
    public boolean exists() {
        Query query = Query.query(Criteria.where("_id").is(SINGLETON_ID));
        return mongoTemplate.exists(query, COLLECTION_NAME);
    }

    @Override
    public void incrementAdministrators() {
        Query query = Query.query(Criteria.where("_id").is(SINGLETON_ID));
        Update update = new Update().inc("activeAdministratorCount", 1).inc("version", 1);
        long matched = mongoTemplate.updateFirst(query, update, COLLECTION_NAME).getMatchedCount();
        if (matched != 1L) {
            throw new PlatformAuthorityStateMissingException("Platform authority state document is missing");
        }
    }

    @Override
    public boolean decrementAdministratorsIfMoreThanOne() {
        Query query = Query.query(
                Criteria.where("_id").is(SINGLETON_ID)
                        .and("activeAdministratorCount").gt(1)
        );
        Update update = new Update().inc("activeAdministratorCount", -1).inc("version", 1);
        return mongoTemplate.updateFirst(query, update, COLLECTION_NAME).getMatchedCount() == 1L;
    }

    @Override
    public void initialize() {
        PlatformAuthorityStateDocument doc = new PlatformAuthorityStateDocument(
                SINGLETON_ID,
                1L,
                1L
        );
        try {
            mongoTemplate.insert(doc, COLLECTION_NAME);
        } catch (DuplicateKeyException e) {
            throw new PlatformAlreadyBootstrappedException("Platform authority state already exists", e);
        }
    }
}
