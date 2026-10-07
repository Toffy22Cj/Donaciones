package com.traceability.app.application.campaign;

import com.traceability.contracts.CampaignAuditFactsDTO;
import com.traceability.contracts.CampaignAuditFactsPort;
import com.traceability.convocatoria.application.query.CampaignFundingFacts;
import com.traceability.convocatoria.application.query.CampaignFundingFactsQuery;
import com.traceability.core.application.query.CampaignDeliveryFactsQuery;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Optional;

/**
 * Productor de {@link CampaignAuditFactsPort} (ADR-040; plan B5, DD-37): cruza {@code convocatoria} (estado, meta,
 * política y {@code clearedAmount}) y {@code core} (unidades entregadas y receptores distintos). Lecturas
 * independientes, cada una con su instante (DD-35). Los hechos son efímeros: no se guardan (DD-34).
 */
@Component
public class CampaignAuditFactsProducer implements CampaignAuditFactsPort {

    private final CampaignFundingFactsQuery funding;
    private final CampaignDeliveryFactsQuery deliveries;
    private final Clock clock;

    public CampaignAuditFactsProducer(CampaignFundingFactsQuery funding, CampaignDeliveryFactsQuery deliveries,
                                      ObjectProvider<Clock> clock) {
        this.funding = funding;
        this.deliveries = deliveries;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    @Override
    public Optional<CampaignAuditFactsDTO> getCampaignAuditFacts(String campaignRef) {
        return funding.fundingFactsOf(campaignRef).map(f -> {
            CampaignDeliveryFactsQuery.DeliveryFacts d = deliveries.deliveriesOf(campaignRef);
            return new CampaignAuditFactsDTO(f.campaignRef(), f.status(), f.organizationRef(), amount(f.targetAmount()),
                    f.targetPolicy(), amount(f.clearedAmount()), d.unitsDelivered(), d.distinctRecipients(),
                    f.currency(), f.readAt(), d.readAt(), clock.instant());
        });
    }

    private static BigDecimal amount(Long value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    /** Usado por la ruta pública: el código de CV-07 a su {@code campaignRef}, que nunca sale de {@code app}. */
    Optional<String> campaignRefOfPublicCode(String publicCode) {
        return funding.campaignRefOfPublicCode(publicCode);
    }
}
