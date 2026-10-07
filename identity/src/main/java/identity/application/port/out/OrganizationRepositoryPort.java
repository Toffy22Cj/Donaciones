package identity.application.port.out;

import identity.domain.exception.OrganizationNotFoundException;
import identity.domain.model.Organization;
import identity.domain.model.OrganizationId;

public interface OrganizationRepositoryPort {
    /**
     * Finds an organization by its unique ID.
     * @param organizationId the organization ID to search for
     * @return the organization if found
     * @throws OrganizationNotFoundException if the organization does not exist
     */
    Organization findById(OrganizationId organizationId);

    /**
     * Saves a new or modified organization.
     * @param organization the organization to save
     */
    void save(Organization organization);
}
