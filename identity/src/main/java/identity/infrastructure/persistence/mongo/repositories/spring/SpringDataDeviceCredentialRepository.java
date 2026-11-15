package identity.infrastructure.persistence.mongo.repositories.spring;

import identity.infrastructure.persistence.mongo.documents.DeviceCredentialDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Registra {@link DeviceCredentialDocument} al arrancar, para que sus índices se creen fuera de transacciones. */
public interface SpringDataDeviceCredentialRepository extends MongoRepository<DeviceCredentialDocument, String> {
}
