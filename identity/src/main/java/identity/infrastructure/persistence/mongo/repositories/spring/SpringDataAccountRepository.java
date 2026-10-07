package identity.infrastructure.persistence.mongo.repositories.spring;

import identity.infrastructure.persistence.mongo.documents.AccountDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface SpringDataAccountRepository extends MongoRepository<AccountDocument, String> {
    java.util.Optional<AccountDocument> findByEmail(String email);
}
