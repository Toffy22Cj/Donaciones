package identity.infrastructure.persistence.mongo.repositories.spring;

import identity.infrastructure.persistence.mongo.documents.OrganizationDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface SpringDataOrganizationRepository extends MongoRepository<OrganizationDocument, String> {
}
