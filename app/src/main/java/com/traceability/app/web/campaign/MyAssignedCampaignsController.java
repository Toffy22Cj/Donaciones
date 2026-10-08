package com.traceability.app.web.campaign;

import com.traceability.api.web.CurrentActor;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.convocatoria.application.query.AssignedCampaignsQuery;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * Empleado: mis convocatorias asignadas (autorización (3) de Carlos, §3.4): {@code GET /api/v1/me/campaigns} →
 * {@code 200 {items: [{campaignRef, publicCode, title, status, actingRole, assignedAt}]}}, {@code no-store}. Cualquier
 * cuenta autenticada ve solo <b>sus</b> asignaciones activas en su organización actual; sin organización, la lista
 * vacía. No recibe parámetros: nadie puede pedir las de otro.
 */
@RestController
public class MyAssignedCampaignsController {

    public record Item(String campaignRef, String publicCode, String title, String status, String actingRole,
                       Instant assignedAt) {}

    public record Page(List<Item> items) {}

    private final AssignedCampaignsQuery assigned;

    public MyAssignedCampaignsController(AssignedCampaignsQuery assigned) {
        this.assigned = assigned;
    }

    @GetMapping("/api/v1/me/campaigns")
    public ResponseEntity<Page> mine(@CurrentActor AuthorizationPrincipal principal) {
        List<Item> items = assigned.forResponsible(principal.accountId(), principal.organizationId()).stream()
                .map(c -> new Item(c.campaignRef(), c.publicCode(), c.title(), c.status(), c.actingRole(), c.assignedAt()))
                .toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Page(items));
    }
}
