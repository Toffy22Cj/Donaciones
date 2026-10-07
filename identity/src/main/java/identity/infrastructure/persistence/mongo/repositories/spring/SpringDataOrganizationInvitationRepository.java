package identity.infrastructure.persistence.mongo.repositories.spring;

import identity.infrastructure.persistence.mongo.documents.OrganizationInvitationDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Registra {@link OrganizationInvitationDocument} al arrancar, para que sus índices se creen fuera de transacciones. */
public interface SpringDataOrganizationInvitationRepository extends MongoRepository<OrganizationInvitationDocument, String> {
}
