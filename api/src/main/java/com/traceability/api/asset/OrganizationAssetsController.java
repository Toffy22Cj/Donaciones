package com.traceability.api.asset;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.traceability.api.web.CurrentActor;
import com.traceability.core.application.port.out.PhysicalAssetOperationalReadPort;
import com.traceability.core.domain.event.HumanActor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * {@code GET /api/v1/organizations/{organizationId}/physical-assets} (P2.7): los activos de la organización para el
 * panel, con los campos de la lectura operacional (matriz §4b): sin {@code donorRef}, datos financieros ni
 * genealogía. {@code ADMINISTRATOR} o {@code EMPLOYEE}; otra organización o inexistente → el mismo 403. Una página con
 * tope de 200, sin {@code nextCursor} (como DD-21).
 * <p>
 * Cada ítem lleva además {@code lastEventAt?} (ficha R-6, Enmienda 1, E2, G-9): el {@code occurredAt} del último evento
 * del lote por {@code sequence}, en ISO-8601 UTC, y omitido si no hay fecha. Por eso el ítem tiene su propio registro y
 * la vista individual ({@code OperationalResponse}) no cambia (adenda G-9, P-G9-3).
 */
@RestController
public class OrganizationAssetsController {

    public record Page(List<OrganizationAssetItem> items) {}

    /** Los campos de {@code OperationalResponse} más {@code lastEventAt?} (G-9). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OrganizationAssetItem(String assetRef, String lifecycleStatus, String currentCustodianRef,
                                        String currentLocation, String quantity, String unitOfMeasure,
                                        String campaignRef, String assetType, String lastEventAt) {}

    private final PhysicalAssetOperationalReadPort reads;

    public OrganizationAssetsController(PhysicalAssetOperationalReadPort reads) {
        this.reads = reads;
    }

    @GetMapping("/api/v1/organizations/{organizationId}/physical-assets")
    public Page list(@CurrentActor HumanActor actor, @PathVariable("organizationId") String organizationId) {
        return new Page(reads.listForOrganization(organizationId, actor).stream()
                .map(v -> new OrganizationAssetItem(v.assetRef(), v.lifecycleStatus(), v.currentCustodianRef(),
                        v.currentLocation(), v.quantity().toPlainString(), v.unitOfMeasure(), v.campaignRef(),
                        v.assetType(), v.lastEventAt() == null ? null : v.lastEventAt().toString()))
                .toList());
    }
}
