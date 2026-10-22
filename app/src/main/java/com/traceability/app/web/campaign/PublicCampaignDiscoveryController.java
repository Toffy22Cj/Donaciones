package com.traceability.app.web.campaign;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.traceability.api.web.InvalidRequestFieldException;
import com.traceability.app.application.media.OrganizationLogoService;
import com.traceability.convocatoria.application.query.PublicCampaignDiscoveryQuery;
import com.traceability.convocatoria.application.query.PublicCampaignDiscoveryQuery.Page;
import com.traceability.convocatoria.application.query.PublicCampaignDiscoveryQuery.PublicCampaignSummary;
import com.traceability.convocatoria.application.service.ConvocatoriaLifecycleService;
import identity.application.service.OrganizationLogoReferenceQuery;
import identity.application.service.OrganizationLogoReferenceQuery.PublicOrganization;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/**
 * Descubrimiento público, {@code GET /api/v1/public/campaigns} (P2.6; Q-v2-3: sin filtros, solo {@code ?cursor=};
 * T-35: {@code {items, nextCursor}}, sin {@code nextCursor} en la última página, cursor inválido → 400). Nunca lista
 * {@code PRIVATE_LINK}. Sin {@code campaignRef} ni {@code organizationRef}: solo el nombre público de la organización.
 * El cursor es opaco ({@link DiscoveryCursorCodec}, T-35; DD-53 rehecha).
 * {@code organizationVerified} se calcula con el estado actual de la organización (ficha L-FRONTS, A1; M-07).
 * {@code organizationLogoUrl} solo si la organización tiene logo (ADR-051 F6, Enmienda 1). Nombre, verificación y logo
 * salen de una sola lectura de {@code identity} por ítem (plan de la Tarea 2, P-T2-7).
 */
@RestController
public class PublicCampaignDiscoveryController {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(String publicCode, String title, String organizationName, boolean organizationVerified,
                       String status, String startDate, String endDate, Set<String> acceptedDonationTypes,
                       String currency, String targetAmount, String clearedAmount, String organizationLogoUrl) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Response(List<Item> items, String nextCursor) {}

    private final PublicCampaignDiscoveryQuery discovery;
    private final OrganizationLogoReferenceQuery organizations;

    private final DiscoveryCursorCodec cursors;

    public PublicCampaignDiscoveryController(PublicCampaignDiscoveryQuery discovery,
                                             OrganizationLogoReferenceQuery organizations,
                                             DiscoveryCursorCodec cursors) {
        this.discovery = discovery;
        this.organizations = organizations;
        this.cursors = cursors;
    }

    @GetMapping("/api/v1/public/campaigns")
    public Response discover(@RequestParam(name = "cursor", required = false) String cursor) {
        Page page = discovery.page(cursor == null ? null : decode(cursor));
        return new Response(page.items().stream().map(this::item).toList(),
                page.lastPublicCode() == null ? null : encode(page.lastPublicCode()));
    }

    private Item item(PublicCampaignSummary s) {
        PublicOrganization o = organizations.publicOrganization(s.organizationRef());
        return new Item(s.publicCode(), s.title(), o.name(), o.verified(), s.status(), s.startDate().toString(),
                s.endDate().toString(), s.acceptedDonationTypes(), s.currency(),
                s.targetAmount() == null ? null : s.targetAmount().toString(),
                s.clearedAmount() == null ? null : s.clearedAmount().toString(),
                o.hasLogo() ? OrganizationLogoService.publicLogoUrl(s.publicCode()) : null);
    }

    private String encode(String publicCode) {
        return cursors.encode(publicCode);
    }

    private String decode(String cursor) {
        return cursors.decode(cursor)
                .filter(code -> ConvocatoriaLifecycleService.PUBLIC_CODE_FORMAT.matcher(code).matches())
                .orElseThrow(() -> new InvalidRequestFieldException("cursor"));
    }
}
