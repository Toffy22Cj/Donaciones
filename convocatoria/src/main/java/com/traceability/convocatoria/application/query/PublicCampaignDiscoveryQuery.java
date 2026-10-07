package com.traceability.convocatoria.application.query;

import com.traceability.convocatoria.application.port.out.CampaignFundingLedgerRepositoryPort;
import com.traceability.convocatoria.application.port.out.ConvocatoriaRepositoryPort;
import com.traceability.convocatoria.domain.model.CampaignFundingLedger;
import com.traceability.convocatoria.domain.model.Convocatoria;
import com.traceability.convocatoria.domain.model.ConvocatoriaConfiguration;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Descubrimiento público (P2.6; Q-v2-3: sin filtros, solo {@code ?cursor=}): convocatorias {@code PUBLIC} y
 * {@code OPEN}, ordenadas por {@code publicCode}. <b>Nunca</b> {@code PRIVATE_LINK} (§3.3): el filtro está en la
 * consulta. {@code organizationRef} es interno: lo usa {@code app} para el nombre público y no sale.
 */
@Component
public class PublicCampaignDiscoveryQuery {

    public static final int PAGE_SIZE = 20;

    public record PublicCampaignSummary(String organizationRef, String publicCode, String title, String status,
                                        Instant startDate, Instant endDate, Set<String> acceptedDonationTypes,
                                        String currency, Long targetAmount, Long clearedAmount) {}

    /** {@code lastPublicCode} es {@code null} si no hay más páginas. */
    public record Page(List<PublicCampaignSummary> items, String lastPublicCode) {}

    private final ConvocatoriaRepositoryPort convocatorias;
    private final CampaignFundingLedgerRepositoryPort ledgers;

    public PublicCampaignDiscoveryQuery(ConvocatoriaRepositoryPort convocatorias, CampaignFundingLedgerRepositoryPort ledgers) {
        this.convocatorias = convocatorias;
        this.ledgers = ledgers;
    }

    public Page page(String afterPublicCode) {
        List<Convocatoria> found = convocatorias.findPublicOpenAfter(afterPublicCode, PAGE_SIZE + 1);
        boolean more = found.size() > PAGE_SIZE;
        List<PublicCampaignSummary> items = found.stream().limit(PAGE_SIZE).map(this::summary).toList();
        return new Page(items, more ? items.get(items.size() - 1).publicCode() : null);
    }

    private PublicCampaignSummary summary(Convocatoria c) {
        ConvocatoriaConfiguration config = c.getConfiguration();
        boolean monetary = config.acceptsMonetary();
        return new PublicCampaignSummary(c.getOrganizationRef(), c.getPublicCode(), c.getTitle(), c.getStatus().name(),
                c.getStartDate(), c.getEndDate(),
                config.acceptedDonationTypes().stream().map(Enum::name).collect(Collectors.toCollection(TreeSet::new)),
                monetary ? config.currency() : null, monetary ? config.targetAmount() : null,
                monetary ? ledgers.findByCampaignRef(c.getCampaignRef()).map(CampaignFundingLedger::clearedAmount).orElse(0L) : null);
    }
}
