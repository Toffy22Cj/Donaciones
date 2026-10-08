package com.traceability.app.web.organization;

import com.traceability.api.web.CurrentActor;
import com.traceability.api.web.InvalidRequestFieldException;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import identity.application.authorization.AuthorizationAuditActorMapper;
import identity.application.service.CreateOrganizationService;
import identity.domain.model.AccountId;
import identity.domain.model.Organization;
import identity.domain.model.OrganizationType;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Crear organización (R9; autorización (3) de Carlos, §1 y §3.1): {@code POST /api/v1/organizations}. Cualquier
 * usuario registrado sin organización; queda como {@code REPRESENTATIVE} y la organización nace
 * {@code PENDING_VERIFICATION} hasta que la apruebe la plataforma (operación de dominio de ADR-026, sin reglas nuevas).
 * <ul>
 *   <li>{@code 201 {organizationId, verificationStatus}};</li>
 *   <li>{@code type} distinto de {@code FOUNDATION}/{@code COMPANY}, o {@code name} vacío o de más de 200 caracteres →
 *   400, sin llamar al dominio;</li>
 *   <li>la cuenta ya pertenece a una organización → 409 ({@code AccountAlreadyBelongsToOrganization}).</li>
 * </ul>
 * Sin {@code Command-Id}: Identity no lo usa (DH-34, DD-56); un reintento recibe 409.
 */
@RestController
public class OrganizationRegistrationController {

    public record CreateOrganizationRequest(String type, String name) {}

    public record CreateOrganizationResponse(String organizationId, String verificationStatus) {}

    private final CreateOrganizationService organizations;
    private final AuthorizationAuditActorMapper auditActors;

    public OrganizationRegistrationController(CreateOrganizationService organizations,
                                              AuthorizationAuditActorMapper auditActors) {
        this.organizations = organizations;
        this.auditActors = auditActors;
    }

    @PostMapping("/api/v1/organizations")
    @ResponseStatus(HttpStatus.CREATED)
    public CreateOrganizationResponse create(@CurrentActor AuthorizationPrincipal principal,
                                             @RequestBody(required = false) CreateOrganizationRequest request) {
        if (request == null || !StringUtils.hasText(request.type())) {
            throw new InvalidRequestFieldException("type");
        }
        OrganizationType type;
        try {
            type = OrganizationType.valueOf(request.type());
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestFieldException("type");
        }
        // el dominio admite organizaciones sin nombre (DD-04); por HTTP es obligatorio: la cola y CV-07 lo muestran
        if (!StringUtils.hasText(request.name()) || request.name().strip().length() > Organization.NAME_MAX_LENGTH) {
            throw new InvalidRequestFieldException("name");
        }
        Organization created = organizations.createOrganization(auditActors.toAuditActor(principal), type,
                new AccountId(principal.accountId()), request.name());
        return new CreateOrganizationResponse(created.getOrganizationId().value(),
                created.getVerificationStatus().name());
    }
}
