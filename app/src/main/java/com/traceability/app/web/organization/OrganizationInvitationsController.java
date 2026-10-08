package com.traceability.app.web.organization;

import com.traceability.api.web.CurrentActor;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import identity.application.service.InviteMemberService;
import identity.application.service.OrganizationInvitationsQuery;
import identity.application.service.RevokeInvitationService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * Invitaciones de una organización (ADR-049 D5, D6; autorización (3) de Carlos, §3.3). {@code ADMINISTRATOR} o
 * {@code REPRESENTATIVE} de la organización; el resto, el mismo 403, siempre antes de validar la entrada.
 * <ul>
 *   <li>{@code POST …/invitations {email, role}} → {@code 202 {invitationId, role, expiresAt}}, <b>igual exista o no
 *   una cuenta con ese email</b>; rol que no sea {@code ADMINISTRATOR}/{@code EMPLOYEE} → 400; email mal formado → 400;</li>
 *   <li>{@code GET …/invitations} → {@code 200 {items: [{invitationId, emailMasked, role, createdAt, expiresAt,
 *   delivery}]}}, pendientes y sin caducar, sin token ni hash, {@code no-store};</li>
 *   <li>{@code POST …/invitations/{invitationId}/revoke} → {@code 200 {invitationId, status: "REVOKED"}}; ya no
 *   pendiente → 409; de otra organización o inexistente → 403.</li>
 * </ul>
 */
@RestController
public class OrganizationInvitationsController {

    public record InviteRequest(String email, String role) {}

    public record InviteResponse(String invitationId, String role, Instant expiresAt) {}

    public record Invitation(String invitationId, String emailMasked, String role, Instant createdAt, Instant expiresAt,
                             String delivery) {}

    public record Page(List<Invitation> items) {}

    public record RevokeResponse(String invitationId, String status) {}

    private final InviteMemberService invitations;
    private final OrganizationInvitationsQuery pending;
    private final RevokeInvitationService revocations;

    public OrganizationInvitationsController(InviteMemberService invitations, OrganizationInvitationsQuery pending,
                                             RevokeInvitationService revocations) {
        this.invitations = invitations;
        this.pending = pending;
        this.revocations = revocations;
    }

    @PostMapping("/api/v1/organizations/{organizationId}/invitations")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public InviteResponse invite(@CurrentActor AuthorizationPrincipal principal,
                                 @PathVariable("organizationId") String organizationId,
                                 @RequestBody(required = false) InviteRequest request) {
        InviteMemberService.IssuedInvitation issued = invitations.invite(principal, organizationId,
                request == null ? null : request.email(), request == null ? null : request.role());
        return new InviteResponse(issued.invitationId(), issued.role(), issued.expiresAt());
    }

    @GetMapping("/api/v1/organizations/{organizationId}/invitations")
    public ResponseEntity<Page> list(@CurrentActor AuthorizationPrincipal principal,
                                     @PathVariable("organizationId") String organizationId) {
        List<Invitation> items = pending.pending(principal, organizationId).stream()
                .map(i -> new Invitation(i.invitationId(), i.emailMasked(), i.role(), i.createdAt(), i.expiresAt(),
                        i.delivery())).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Page(items));
    }

    @PostMapping("/api/v1/organizations/{organizationId}/invitations/{invitationId}/revoke")
    public RevokeResponse revoke(@CurrentActor AuthorizationPrincipal principal,
                                 @PathVariable("organizationId") String organizationId,
                                 @PathVariable("invitationId") String invitationId) {
        revocations.revoke(principal, organizationId, invitationId);
        return new RevokeResponse(invitationId, "REVOKED");
    }
}
