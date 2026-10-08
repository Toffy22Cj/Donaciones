package identity.infrastructure.persistence.mongo.repositories.spring;

import identity.infrastructure.persistence.mongo.documents.OrganizationDocument;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Collection;
import java.util.List;

public interface SpringDataOrganizationRepository extends MongoRepository<OrganizationDocument, String> {

    /** Cola de verificación: {@code _id} es un ULID, así que el orden por id es el de creación. */
    List<OrganizationDocument> findByVerificationStatusInAndOrganizationIdGreaterThanOrderByOrganizationIdAsc(
            Collection<String> verificationStatuses, String afterOrganizationId, Pageable page);
}
