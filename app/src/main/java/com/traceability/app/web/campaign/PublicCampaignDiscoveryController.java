package com.traceability.app.web.campaign;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.traceability.api.web.InvalidRequestFieldException;
import com.traceability.contracts.organization.OrganizationPublicNamePort;
import com.traceability.convocatoria.application.query.PublicCampaignDiscoveryQuery;
import com.traceability.convocatoria.application.query.PublicCampaignDiscoveryQuery.Page;
import com.traceability.convocatoria.application.query.PublicCampaignDiscoveryQuery.PublicCampaignSummary;
import com.traceability.convocatoria.application.service.ConvocatoriaLifecycleService;
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
 */
@RestController
public class PublicCampaignDiscoveryController {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(String publicCode, String title, String organizationName, String status, String startDate,
                       String endDate, Set<String> acceptedDonationTypes, String currency, String targetAmount,
                       String clearedAmount) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Response(List<Item> items, String nextCursor) {}

    private final PublicCampaignDiscoveryQuery discovery;
    private final OrganizationPublicNamePort organizationNames;

    private final DiscoveryCursorCodec cursors;

    public PublicCampaignDiscoveryController(PublicCampaignDiscoveryQuery discovery,
                                             OrganizationPublicNamePort organizationNames,
                                             @org.springframework.beans.factory.annotation.Value(
                                                     "${traceability.discovery.cursor-key:}") String cursorKey) {
        this.discovery = discovery;
        this.organizationNames = organizationNames;
        this.cursors = cursorKey.isBlank() ? DiscoveryCursorCodec.withRandomKey()
                : DiscoveryCursorCodec.fromConfiguredKey(cursorKey);
    }

    @GetMapping("/api/v1/public/campaigns")
    public Response discover(@RequestParam(name = "cursor", required = false) String cursor) {
        Page page = discovery.page(cursor == null ? null : decode(cursor));
        return new Response(page.items().stream().map(this::item).toList(),
                page.lastPublicCode() == null ? null : encode(page.lastPublicCode()));
    }

    private Item item(PublicCampaignSummary s) {
        return new Item(s.publicCode(), s.title(), organizationNames.findPublicName(s.organizationRef()).orElse(null),
                s.status(), s.startDate().toString(), s.endDate().toString(), s.acceptedDonationTypes(), s.currency(),
                s.targetAmount() == null ? null : s.targetAmount().toString(),
                s.clearedAmount() == null ? null : s.clearedAmount().toString());
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
