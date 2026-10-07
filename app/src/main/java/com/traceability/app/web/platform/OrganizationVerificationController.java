package com.traceability.app.web.platform;

import com.traceability.api.web.CurrentActor;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import identity.application.service.RejectOrganizationService;
import identity.application.service.RequestOrganizationInformationService;
import identity.application.service.VerifyOrganizationService;
import identity.domain.model.OrganizationId;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Verificar (golden path, paso 1; plan B6-a §2.4), rechazar y pedir información sobre una organización (matriz §1;
 * P2.8). Solo el administrador de plataforma. Sin {@code Command-Id} (DD-07): el estado impide repetir. Sobre una
 * organización ya {@code VERIFIED} o {@code REJECTED}, las tres dan 409 ({@code InvalidVerificationTransition}, regla
 * de dominio de ADR-038; `[DECISIÓN DELEGADA — pendiente de ratificar por Carlos]` DD-48). Una organización
 * inexistente, 404 (solo la ve la plataforma).
 */
@RestController
public class OrganizationVerificationController {

    public record VerificationResponse(String organizationId, String verificationStatus) {}

    public record InformationRequest(String message) {}

    private final VerifyOrganizationService verifications;
    private final RejectOrganizationService rejections;
    private final RequestOrganizationInformationService informationRequests;

    public OrganizationVerificationController(VerifyOrganizationService verifications,
                                              RejectOrganizationService rejections,
                                              RequestOrganizationInformationService informationRequests) {
        this.verifications = verifications;
        this.rejections = rejections;
        this.informationRequests = informationRequests;
    }

    @PostMapping("/api/v1/platform/organizations/{organizationId}/verify")
    public VerificationResponse verify(@CurrentActor AuthorizationPrincipal principal,
                                       @PathVariable("organizationId") String organizationId) {
        verifications.verifyOrganization(principal, new OrganizationId(organizationId));
        return new VerificationResponse(organizationId, "VERIFIED");
    }

    @PostMapping("/api/v1/platform/organizations/{organizationId}/reject")
    public VerificationResponse reject(@CurrentActor AuthorizationPrincipal principal,
                                       @PathVariable("organizationId") String organizationId) {
        rejections.rejectOrganization(principal, new OrganizationId(organizationId));
        return new VerificationResponse(organizationId, "REJECTED");
    }

    /** {@code message}: obligatorio, hasta 2000 caracteres (dominio, {@code InformationRequestMessage}). */
    @PostMapping("/api/v1/platform/organizations/{organizationId}/request-information")
    public VerificationResponse requestInformation(@CurrentActor AuthorizationPrincipal principal,
                                                   @PathVariable("organizationId") String organizationId,
                                                   @RequestBody(required = false) InformationRequest request) {
        // el servicio autoriza antes de validar el mensaje: un no autorizado recibe 403, nunca 400
        informationRequests.requestOrganizationInformation(principal, new OrganizationId(organizationId),
                request == null ? null : request.message());
        return new VerificationResponse(organizationId, "NEEDS_MORE_INFORMATION");
    }
}
