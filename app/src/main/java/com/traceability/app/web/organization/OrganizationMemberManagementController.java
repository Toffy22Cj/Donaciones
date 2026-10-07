package com.traceability.app.web.organization;

import com.traceability.api.web.CurrentActor;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import identity.application.service.OrganizationMemberManagementService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Cambiar el rol de un miembro y quitarlo (ADR-049 D7, D8; autorización (3) de Carlos, §3.3). {@code ADMINISTRATOR} o
 * {@code REPRESENTATIVE} de la organización; un no miembro, otra organización o los roles del representante cambiados
 * por otro → el mismo 403.
 * <ul>
 *   <li>{@code POST …/members/{accountId}/role {role}} → {@code 200 {accountId, roles}}; rol fuera de
 *   {@code ADMINISTRATOR}/{@code EMPLOYEE} → 400; ya en ese estado → 409 {@code MemberAlreadyHasRole}; degradar a un
 *   responsable activo como administrador → 409 {@code ActiveCampaignResponsible};</li>
 *   <li>{@code POST …/members/{accountId}/remove} → {@code 200 {accountId, removed: true}}; el representante → 409
 *   {@code RepresentativeTransferRequired}; un responsable activo → 409 {@code ActiveCampaignResponsible}.</li>
 * </ul>
 */
@RestController
public class OrganizationMemberManagementController {

    public record RoleRequest(String role) {}

    public record RoleResponse(String accountId, List<String> roles) {}

    public record RemoveResponse(String accountId, boolean removed) {}

    private final OrganizationMemberManagementService members;

    public OrganizationMemberManagementController(OrganizationMemberManagementService members) {
        this.members = members;
    }

    @PostMapping("/api/v1/organizations/{organizationId}/members/{accountId}/role")
    public RoleResponse changeRole(@CurrentActor AuthorizationPrincipal principal,
                                   @PathVariable("organizationId") String organizationId,
                                   @PathVariable("accountId") String accountId,
                                   @RequestBody(required = false) RoleRequest request) {
        OrganizationMemberManagementService.MemberRoles changed = members.changeRole(principal, organizationId, accountId,
                request == null ? null : request.role());
        return new RoleResponse(changed.accountId(), changed.roles());
    }

    @PostMapping("/api/v1/organizations/{organizationId}/members/{accountId}/remove")
    public RemoveResponse remove(@CurrentActor AuthorizationPrincipal principal,
                                 @PathVariable("organizationId") String organizationId,
                                 @PathVariable("accountId") String accountId) {
        members.remove(principal, organizationId, accountId);
        return new RemoveResponse(accountId, true);
    }
}
