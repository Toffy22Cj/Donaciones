package com.traceability.app.web.account;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.traceability.api.web.CurrentActor;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import identity.application.service.OrganizationMembersQuery;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * ID-12, {@code GET /api/v1/organizations/{organizationId}/members} (P2.7): {@code {items: [{accountId, roles,
 * status}]}}, sin email. {@code ADMINISTRATOR} o {@code REPRESENTATIVE} de la organización; el resto, el mismo 403.
 */
@RestController
public class OrganizationMembersController {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Member(String accountId, List<String> roles, String status) {}

    public record Page(List<Member> items) {}

    private final OrganizationMembersQuery members;

    public OrganizationMembersController(OrganizationMembersQuery members) {
        this.members = members;
    }

    @GetMapping("/api/v1/organizations/{organizationId}/members")
    public Page list(@CurrentActor AuthorizationPrincipal principal,
                     @PathVariable("organizationId") String organizationId) {
        return new Page(members.members(principal, organizationId).stream()
                .map(m -> new Member(m.accountId(), m.roles(), m.status())).toList());
    }
}
