package com.traceability.app.web.organization;

import com.traceability.api.web.CurrentActor;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import identity.application.service.AcceptInvitationService;
import identity.domain.exception.InvitationNotAcceptableException;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Aceptar una invitación (ADR-049 D3, D4): {@code POST /api/v1/invitations/accept} con JWT y el token en el
 * <b>cuerpo</b> {@code {token}}. La web lo lee del fragmento del enlace ({@code /invitaciones#token=…}) y lo borra de la
 * barra. Un token en la query nunca se acepta: la petición se rechaza igual que un token inválido (el mismo 403
 * {@code InvitationNotAcceptable}, DD-63). Respuesta: {@code 200 {organizationId, roles}}.
 */
@RestController
public class InvitationAcceptanceController {

    public record AcceptRequest(String token) {}

    public record AcceptResponse(String organizationId, List<String> roles) {}

    private final AcceptInvitationService acceptance;

    public InvitationAcceptanceController(AcceptInvitationService acceptance) {
        this.acceptance = acceptance;
    }

    @PostMapping("/api/v1/invitations/accept")
    public AcceptResponse accept(@CurrentActor AuthorizationPrincipal principal,
                                 @RequestParam MultiValueMap<String, String> query,
                                 @RequestBody(required = false) AcceptRequest request) {
        // con cuerpo JSON, los parámetros son solo los de la query
        if (!query.isEmpty()) {
            throw new InvitationNotAcceptableException("Invitation tokens are never accepted in the URL");
        }
        AcceptInvitationService.Accepted accepted = acceptance.accept(principal, request == null ? null : request.token());
        return new AcceptResponse(accepted.organizationId(), accepted.roles());
    }
}
