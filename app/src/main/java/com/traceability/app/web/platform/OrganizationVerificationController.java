package com.traceability.app.web.platform;

import com.traceability.api.web.CurrentActor;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import identity.application.service.VerifyOrganizationService;
import identity.domain.model.OrganizationId;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Verificar organización (golden path, paso 1; plan B6-a §2.4). Sin {@code Command-Id} (Q-B6A-4 (a),
 * `[DECISIÓN DELEGADA — pendiente de ratificar por Carlos]` DD-07): el estado ya impide verificar dos veces, y un
 * reintento recibe 409.
 */
@RestController
public class OrganizationVerificationController {

    public record VerificationResponse(String organizationId, String verificationStatus) {}

    private final VerifyOrganizationService verifications;

    public OrganizationVerificationController(VerifyOrganizationService verifications) {
        this.verifications = verifications;
    }

    @PostMapping("/api/v1/platform/organizations/{organizationId}/verify")
    public VerificationResponse verify(@CurrentActor AuthorizationPrincipal principal,
                                       @PathVariable("organizationId") String organizationId) {
        verifications.verifyOrganization(principal, new OrganizationId(organizationId));
        return new VerificationResponse(organizationId, "VERIFIED");
    }
}
