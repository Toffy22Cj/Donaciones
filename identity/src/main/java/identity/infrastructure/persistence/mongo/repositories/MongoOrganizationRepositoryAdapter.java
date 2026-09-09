package identity.infrastructure.persistence.mongo.repositories;

import identity.application.port.out.OrganizationRepositoryPort;
import identity.domain.model.Organization;
import identity.domain.model.OrganizationId;
import identity.infrastructure.persistence.mongo.mappers.OrganizationMapper;
import identity.infrastructure.persistence.mongo.repositories.spring.SpringDataOrganizationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class MongoOrganizationRepositoryAdapter implements OrganizationRepositoryPort {

    private final SpringDataOrganizationRepository repository;

    @Override
    public void save(Organization organization) {
        repository.save(OrganizationMapper.toDocument(organization));
    }

    @Override
    public Organization findById(OrganizationId organizationId) {
        return repository.findById(organizationId.value())
            .map(OrganizationMapper::toDomain)
            .orElseThrow(() -> new identity.domain.exception.OrganizationNotFoundException("Organization not found: " + organizationId.value()));
    }
}
