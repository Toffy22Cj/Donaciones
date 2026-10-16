package com.traceability.app.web.organization;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.traceability.api.web.CurrentActor;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import identity.application.service.OrganizationVerificationStatusQuery;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ficha S-18, {@code GET /api/v1/organizations/{organizationId}}: {@code 200 {organizationId, name?, type,
 * verificationStatus, informationRequest?}}, {@code no-store}; {@code informationRequest} solo con
 * {@code NEEDS_MORE_INFORMATION}. {@code ADMINISTRATOR} o {@code REPRESENTATIVE} de la organización; el resto, el
 * mismo 403 (DD-01). Solo traduce (ADR-041 §2.1).
 */
@RestController
public class OrganizationVerificationStatusController {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OrganizationStatus(String organizationId, String name, String type, String verificationStatus,
                                     String informationRequest) {}

    private final OrganizationVerificationStatusQuery query;

    public OrganizationVerificationStatusController(OrganizationVerificationStatusQuery query) {
        this.query = query;
    }

    @GetMapping("/api/v1/organizations/{organizationId}")
    public ResponseEntity<OrganizationStatus> status(@CurrentActor AuthorizationPrincipal principal,
                                                     @PathVariable("organizationId") String organizationId) {
        OrganizationVerificationStatusQuery.StatusView v = query.status(principal, organizationId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new OrganizationStatus(
                v.organizationId(), v.name(), v.type(), v.verificationStatus(), v.informationRequest()));
    }
}
