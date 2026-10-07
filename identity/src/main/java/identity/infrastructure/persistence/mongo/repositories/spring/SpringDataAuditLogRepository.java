package identity.infrastructure.persistence.mongo.repositories.spring;

import identity.infrastructure.persistence.mongo.documents.AuditLogEntryDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface SpringDataAuditLogRepository extends MongoRepository<AuditLogEntryDocument, String> {
}
