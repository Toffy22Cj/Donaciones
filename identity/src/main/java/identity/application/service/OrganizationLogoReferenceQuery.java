package identity.application.service;

import com.traceability.contracts.authorization.AuthorizationPrincipal;
import identity.application.authorization.PlatformAuthorizationPolicy;
import identity.application.authorization.PlatformCommandType;
import identity.application.port.out.OrganizationRepositoryPort;
import identity.domain.exception.InsufficientPlatformAuthorityException;
import identity.domain.exception.OrganizationAccessDeniedException;
import identity.domain.exception.OrganizationNotFoundException;
import identity.domain.model.Organization;
import identity.domain.model.OrganizationId;
import identity.domain.model.VerificationStatus;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Lecturas de la referencia del logo (ADR-051 F6; plan de la Tarea 2 §4.3 y §4.6). Lecturas de un documento, sin
 * transacción, como {@link OrganizationPublicNameQuery}. Una organización inexistente se lee sin logo.
 */
@Service
public class OrganizationLogoReferenceQuery {

    /**
     * Lo público de una organización en una sola lectura (P-T2-7): nombre (DD-04), si está {@code VERIFIED} (como
     * {@code OrganizationVerificationPort}) y si tiene logo. Sin {@code organizationId}.
     */
    public record PublicOrganization(String name, boolean verified, boolean hasLogo) {}

    private final OrganizationRepositoryPort organizations;
    private final PlatformAuthorizationPolicy platformPolicy;

    public OrganizationLogoReferenceQuery(OrganizationRepositoryPort organizations,
                                          PlatformAuthorizationPolicy platformPolicy) {
        this.organizations = organizations;
        this.platformPolicy = platformPolicy;
    }

    /** Para la ruta pública por {@code publicCode}: la visibilidad ya la decidió la convocatoria (Enmienda 1). */
    public Optional<String> logoObjectKeyOf(String organizationId) {
        return find(organizationId).map(Organization::getLogoObjectKey);
    }

    /**
     * Ruta autenticada (Enmienda 1): un miembro de esa organización, con cualquier rol, o un administrador de
     * plataforma ({@link PlatformCommandType#READ_ORGANIZATION_LOGO}, P-T2-2). El resto, el mismo 403 (DD-01).
     */
    public Optional<String> logoObjectKeyForReader(AuthorizationPrincipal principal, String organizationId) {
        if (principal == null) {
            throw new OrganizationAccessDeniedException("Reading the logo requires membership or platform authority");
        }
        boolean member = organizationId != null && organizationId.equals(principal.organizationId());
        if (!member) {
            try {
                platformPolicy.authorize(principal, PlatformCommandType.READ_ORGANIZATION_LOGO);
            } catch (InsufficientPlatformAuthorityException e) {
                throw new OrganizationAccessDeniedException("Reading the logo requires membership or platform authority");
            }
        }
        return logoObjectKeyOf(organizationId);
    }

    public PublicOrganization publicOrganization(String organizationId) {
        return find(organizationId)
                .map(o -> new PublicOrganization(o.getName(), o.getVerificationStatus() == VerificationStatus.VERIFIED,
                        o.getLogoObjectKey() != null))
                .orElse(new PublicOrganization(null, false, false));
    }

    private Optional<Organization> find(String organizationId) {
        if (organizationId == null || organizationId.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(organizations.findById(new OrganizationId(organizationId)));
        } catch (OrganizationNotFoundException e) {
            return Optional.empty();
        }
    }
}
