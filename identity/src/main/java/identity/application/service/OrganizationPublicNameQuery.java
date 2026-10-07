package identity.application.service;

import com.traceability.contracts.organization.OrganizationPublicNamePort;
import identity.application.port.out.OrganizationRepositoryPort;
import identity.domain.exception.OrganizationNotFoundException;
import identity.domain.model.OrganizationId;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Nombre público de una organización (CV-07; plan B6-a, Q-B6A-1 (a), Carlos, 2026-10-07). Solo devuelve el nombre.
 * Es una lectura de un documento, sin transacción: no es un servicio de escritura (la regla de ArchUnit de identity
 * sobre {@code MongoTransactionRetryHelper} rige para los `*Service`), igual que {@code IdentityPrincipalPortImpl}.
 */
@Service
public class OrganizationPublicNameQuery implements OrganizationPublicNamePort {

    private final OrganizationRepositoryPort organizations;

    public OrganizationPublicNameQuery(OrganizationRepositoryPort organizations) {
        this.organizations = organizations;
    }

    @Override
    public Optional<String> findPublicName(String organizationId) {
        if (organizationId == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(organizations.findById(new OrganizationId(organizationId)).getName());
        } catch (OrganizationNotFoundException e) {
            return Optional.empty();
        }
    }
}
