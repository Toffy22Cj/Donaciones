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

    @Override
    public java.util.List<Organization> findByVerificationStatusAfter(
            java.util.Set<identity.domain.model.VerificationStatus> statuses, String afterOrganizationId, int limit) {
        return repository.findByVerificationStatusInAndOrganizationIdGreaterThanOrderByOrganizationIdAsc(
                        statuses.stream().map(Enum::name).toList(), afterOrganizationId == null ? "" : afterOrganizationId,
                        org.springframework.data.domain.PageRequest.of(0, limit))
                .stream().map(OrganizationMapper::toDomain).toList();
    }
}
