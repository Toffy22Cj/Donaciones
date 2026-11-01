package identity.application.service;

import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.contracts.authorization.AuthorizationRole;
import identity.application.port.out.OrganizationRepositoryPort;
import identity.domain.exception.OrganizationAccessDeniedException;
import identity.domain.exception.OrganizationNotFoundException;
import identity.domain.model.Organization;
import identity.domain.model.OrganizationId;
import org.springframework.stereotype.Service;

/**
 * Estado de verificación de la propia organización (ficha S-18; ADR-038 §2.5): {@code organizationId}, {@code name},
 * {@code type}, {@code verificationStatus} e {@code informationRequest}. Solo {@code ADMINISTRATOR} o
 * {@code REPRESENTATIVE} de esa organización (Q-S18-2); otra organización o una inexistente dan la misma excepción,
 * como {@link OrganizationMembersQuery}. Sin miembros ni emails. Lectura sin transacción y sin reglas nuevas: que
 * {@code informationRequest} solo exista con {@code NEEDS_MORE_INFORMATION} lo garantiza {@link Organization}.
 */
@Service
public class OrganizationVerificationStatusQuery {

    /** {@code hasLogo}: si la organización tiene logo (ADR-051 F6; ficha S-18, nota del 2026-10-21). */
    public record StatusView(String organizationId, String name, String type, String verificationStatus,
                             String informationRequest, boolean hasLogo,
                             /** Ficha R-7 §4 y test 9: el perfil completo, porque solo lo leen REPRESENTATIVE y ADMINISTRATOR. */
                             identity.domain.model.OrganizationProfile profile) {
        public StatusView(String organizationId, String name, String type, String verificationStatus,
                          String informationRequest, boolean hasLogo) {
            this(organizationId, name, type, verificationStatus, informationRequest, hasLogo,
                    identity.domain.model.OrganizationProfile.EMPTY);
        }
    }

    private final OrganizationRepositoryPort organizations;

    public OrganizationVerificationStatusQuery(OrganizationRepositoryPort organizations) {
        this.organizations = organizations;
    }

    public StatusView status(AuthorizationPrincipal principal, String organizationId) {
        if (principal == null || organizationId == null || !organizationId.equals(principal.organizationId())
                || principal.roles() == null || !(principal.roles().contains(AuthorizationRole.ADMINISTRATOR)
                || principal.roles().contains(AuthorizationRole.REPRESENTATIVE))) {
            throw new OrganizationAccessDeniedException("Reading the organization requires ADMINISTRATOR or REPRESENTATIVE");
        }
        Organization o;
        try {
            o = organizations.findById(new OrganizationId(organizationId));
        } catch (OrganizationNotFoundException e) {
            throw new OrganizationAccessDeniedException("Organization not readable");
        }
        return new StatusView(o.getOrganizationId().value(), o.getName(), o.getType().name(),
                o.getVerificationStatus().name(),
                o.getVerificationInformationRequest() == null ? null : o.getVerificationInformationRequest().value(),
                o.getLogoObjectKey() != null, o.getProfile());
    }
}
