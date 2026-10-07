package com.traceability.convocatoria.application.authorization;

import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.contracts.authorization.AuthorizationRole;
import com.traceability.contracts.authorization.IdentityPrincipalPort;
import com.traceability.convocatoria.domain.exception.ActorNotInCampaignOrganizationException;
import com.traceability.convocatoria.domain.exception.ActorRoleNotAllowedException;
import com.traceability.convocatoria.domain.exception.InvalidResponsibleRecipientException;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Autorización propia del módulo sobre {@link IdentityPrincipalPort}/{@link AuthorizationPrincipal} de
 * {@code contracts} (ADR-037 §4, §5; Enmienda §3.4, §4.1, §4.3; implementation_plan.md §6). No reutiliza
 * {@code RoleAuthorizationPolicy} ni {@code OrganizationBoundaryPolicy} de {@code core}. El respaldo del
 * {@code REPRESENTATIVE} no aplica a ninguna operación del módulo (ADR-037 §5).
 * <p>
 * X2: lo que {@code resolvePrincipal} lance por sí mismo (cuenta inexistente, cuenta inactiva en ADR-038) se
 * propaga sin capturar ni renombrar; se resuelve en ADR-038 (implementation_plan.md §11).
 */
@Component
public class ConvocatoriaAuthorizationPolicy {

    private final IdentityPrincipalPort identityPrincipalPort;

    public ConvocatoriaAuthorizationPolicy(IdentityPrincipalPort identityPrincipalPort) {
        this.identityPrincipalPort = identityPrincipalPort;
    }

    /**
     * Crear convocatoria, editar configuración, {@code AssignEmployeeToCampaign},
     * {@code DesignateAdministratorAsCampaignResponsible}, {@code RemoveResponsible} y cerrar: exclusivamente
     * {@code ADMINISTRATOR} de la organización de la convocatoria.
     */
    public ConvocatoriaActor requireAdministratorOf(String actorAccountId, String organizationRef) {
        Objects.requireNonNull(actorAccountId, "actorAccountId");
        Objects.requireNonNull(organizationRef, "organizationRef");
        AuthorizationPrincipal principal = identityPrincipalPort.resolvePrincipal(actorAccountId);
        if (!organizationRef.equals(principal.organizationId())) {
            throw new ActorNotInCampaignOrganizationException(
                    "Actor " + actorAccountId + " does not belong to organization " + organizationRef);
        }
        if (principal.roles() == null || !principal.roles().contains(AuthorizationRole.ADMINISTRATOR)) {
            throw new ActorRoleNotAllowedException("Actor " + actorAccountId + " is not ADMINISTRATOR");
        }
        return new ConvocatoriaActor(principal.accountId(), principal.organizationId(), principal.roles());
    }

    /**
     * X2: el destinatario de una asignación debe ser {@code EMPLOYEE} (o {@code ADMINISTRATOR}, según la operación)
     * de la misma organización; si no, {@link InvalidResponsibleRecipientException}.
     */
    public void requireRecipient(String recipientAccountId, String organizationRef, AuthorizationRole requiredRole) {
        Objects.requireNonNull(recipientAccountId, "recipientAccountId");
        Objects.requireNonNull(requiredRole, "requiredRole");
        AuthorizationPrincipal recipient = identityPrincipalPort.resolvePrincipal(recipientAccountId);
        if (!organizationRef.equals(recipient.organizationId())
                || recipient.roles() == null || !recipient.roles().contains(requiredRole)) {
            throw new InvalidResponsibleRecipientException("Recipient " + recipientAccountId + " is not "
                    + requiredRole + " of organization " + organizationRef);
        }
    }
}
