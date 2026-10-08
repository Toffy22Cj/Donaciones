package com.traceability.app.web.campaign;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.traceability.api.web.CurrentActor;
import com.traceability.convocatoria.application.query.OrganizationCampaignsQuery;
import com.traceability.convocatoria.application.query.OrganizationCampaignsQuery.AdminCampaignView;
import com.traceability.convocatoria.application.query.OrganizationCampaignsQuery.Responsible;
import com.traceability.core.domain.event.HumanActor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * {@code GET /api/v1/organizations/{organizationId}/campaigns} (matriz §2b; P2.3): listado del panel, solo
 * {@code ADMINISTRATOR} de la organización; otra organización o inexistente → el mismo 403. Una página con tope de
 * 100 y sin {@code nextCursor} (como DD-21). Importes como texto (T-34).
 */
@RestController
public class OrganizationCampaignsController {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(String campaignRef, String publicCode, String title, String status, String visibility,
                       String currency, String targetAmount, String targetPolicy, String clearedAmount,
                       List<Responsible> responsibles, long assignedEmployeeCount) {}

    public record Page(List<Item> items) {}

    private final OrganizationCampaignsQuery campaigns;

    public OrganizationCampaignsController(OrganizationCampaignsQuery campaigns) {
        this.campaigns = campaigns;
    }

    @GetMapping("/api/v1/organizations/{organizationId}/campaigns")
    public Page list(@CurrentActor HumanActor actor, @PathVariable("organizationId") String organizationId) {
        return new Page(campaigns.list(actor.accountId(), organizationId).stream().map(OrganizationCampaignsController::item)
                .toList());
    }

    private static Item item(AdminCampaignView v) {
        return new Item(v.campaignRef(), v.publicCode(), v.title(), v.status(), v.visibility(), v.currency(),
                text(v.targetAmount()), v.targetPolicy(), text(v.clearedAmount()), v.responsibles(),
                v.assignedEmployeeCount());
    }

    private static String text(Long amount) {
        return amount == null ? null : amount.toString();
    }
}
