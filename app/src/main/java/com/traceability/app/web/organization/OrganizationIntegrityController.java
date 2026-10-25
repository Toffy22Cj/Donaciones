package com.traceability.app.web.organization;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.traceability.api.web.CurrentActor;
import com.traceability.app.application.integrity.OrganizationIntegrityQuery;
import com.traceability.app.application.integrity.OrganizationIntegrityQuery.OrganizationIntegrity;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Integridad de la organización (ficha R-6, §6, G-7): {@code GET /api/v1/organizations/{organizationId}/integrity},
 * {@code no-store}. {@code 200 {unanchoredEvents, inPendingBatches, underReviewEvents, lastAnchoredAt?, truncated,
 * checkedAt}}, sin {@code lastVerification} (D-R6-7) ni ningún identificador de lote.
 */
@RestController
public class OrganizationIntegrityController {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record IntegrityResponse(long unanchoredEvents, long inPendingBatches, long underReviewEvents,
                                    String lastAnchoredAt, boolean truncated, String checkedAt) {}

    private final OrganizationIntegrityQuery integrity;

    public OrganizationIntegrityController(OrganizationIntegrityQuery integrity) {
        this.integrity = integrity;
    }

    @GetMapping("/api/v1/organizations/{organizationId}/integrity")
    public ResponseEntity<IntegrityResponse> integrity(@CurrentActor AuthorizationPrincipal principal,
                                                       @PathVariable("organizationId") String organizationId) {
        OrganizationIntegrity i = integrity.integrityOf(principal, organizationId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new IntegrityResponse(i.unanchoredEvents(),
                i.inPendingBatches(), i.underReviewEvents(), i.lastAnchoredAt() == null ? null : i.lastAnchoredAt().toString(),
                i.truncated(), i.checkedAt().toString()));
    }
}
