package identity.application.authorization;

import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.contracts.authorization.AuthorizationRole;
import identity.domain.exception.OrganizationAccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * Quién gestiona los miembros de una organización (autorización (3) de Carlos, §3.3): su {@code ADMINISTRATOR} o su
 * {@code REPRESENTATIVE}. Cualquier otro caso (otro rol, otra organización, sin organización) da la misma
 * {@link OrganizationAccessDeniedException} (DD-01). Se evalúa antes de leer nada y antes de validar la entrada.
 */
@Component
public class OrganizationMembersPolicy {

    public void requireManager(AuthorizationPrincipal principal, String organizationId) {
        if (principal == null || organizationId == null || !organizationId.equals(principal.organizationId())
                || principal.roles() == null || !(principal.roles().contains(AuthorizationRole.ADMINISTRATOR)
                || principal.roles().contains(AuthorizationRole.REPRESENTATIVE))) {
            throw new OrganizationAccessDeniedException("Managing members requires ADMINISTRATOR or REPRESENTATIVE");
        }
    }
}
