package com.traceability.api.asset;

import com.traceability.api.asset.PhysicalAssetDtos.OperationalResponse;
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
 */
@RestController
public class OrganizationAssetsController {

    public record Page(List<OperationalResponse> items) {}

    private final PhysicalAssetOperationalReadPort reads;

    public OrganizationAssetsController(PhysicalAssetOperationalReadPort reads) {
        this.reads = reads;
    }

    @GetMapping("/api/v1/organizations/{organizationId}/physical-assets")
    public Page list(@CurrentActor HumanActor actor, @PathVariable("organizationId") String organizationId) {
        return new Page(reads.listForOrganization(organizationId, actor).stream()
                .map(v -> new OperationalResponse(v.assetRef(), v.lifecycleStatus(), v.currentCustodianRef(),
                        v.currentLocation(), v.quantity().toPlainString(), v.unitOfMeasure(), v.campaignRef()))
                .toList());
    }
}
