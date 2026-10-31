package com.traceability.app.web.organization;

import com.traceability.api.web.CurrentActor;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import identity.application.service.ResubmitOrganizationVerificationService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * [Plan] {@code POST /api/v1/organizations/{organizationId}/verification/resubmit} (ficha R-7 §5): la organización
 * responde a la solicitud de información. {@code REPRESENTATIVE} o {@code ADMINISTRATOR}; el resto, el mismo 403.
 * {@code 200 {verificationStatus: "PENDING_VERIFICATION"}}; desde otro estado, 409 {@code VerificationResubmitNotAllowed}.
 */
@RestController
public class OrganizationVerificationResubmitController {

    public record ResubmitResponse(String verificationStatus) {}

    private final ResubmitOrganizationVerificationService resubmissions;

    public OrganizationVerificationResubmitController(ResubmitOrganizationVerificationService resubmissions) {
        this.resubmissions = resubmissions;
    }

    @PostMapping("/api/v1/organizations/{organizationId}/verification/resubmit")
    public ResponseEntity<ResubmitResponse> resubmit(@CurrentActor AuthorizationPrincipal principal,
                                                     @PathVariable("organizationId") String organizationId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new ResubmitResponse(resubmissions.resubmit(principal, organizationId).name()));
    }
}
