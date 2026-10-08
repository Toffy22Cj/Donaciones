package com.traceability.convocatoria.application.query;

import com.traceability.convocatoria.application.port.out.CampaignFundingLedgerRepositoryPort;
import com.traceability.convocatoria.application.port.out.ConvocatoriaRepositoryPort;
import com.traceability.convocatoria.application.service.ConvocatoriaLifecycleService;
import com.traceability.convocatoria.domain.model.CampaignFundingLedger;
import com.traceability.convocatoria.domain.model.Convocatoria;
import com.traceability.convocatoria.domain.model.ConvocatoriaConfiguration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Optional;

/**
 * Lectura de los hechos de financiación de una convocatoria para su narrativa (plan B5, DD-35 y DD-37). Resuelve
 * también el {@code publicCode} (CV-07) a su {@code campaignRef} interno, que nunca sale de {@code app}.
 */
@Component
public class CampaignFundingFactsQuery {

    private final ConvocatoriaRepositoryPort convocatorias;
    private final CampaignFundingLedgerRepositoryPort ledgers;
    private final Clock clock;

    public CampaignFundingFactsQuery(ConvocatoriaRepositoryPort convocatorias, CampaignFundingLedgerRepositoryPort ledgers,
                                     ObjectProvider<Clock> clock) {
        this.convocatorias = convocatorias;
        this.ledgers = ledgers;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    /** Vacío si el código no tiene la forma de uno emitido (ni se consulta) o no existe, como CV-07. */
    public Optional<String> campaignRefOfPublicCode(String publicCode) {
        if (publicCode == null || !ConvocatoriaLifecycleService.PUBLIC_CODE_FORMAT.matcher(publicCode).matches()) {
            return Optional.empty();
        }
        return convocatorias.findByPublicCode(publicCode).map(Convocatoria::getCampaignRef);
    }

    public Optional<CampaignFundingFacts> fundingFactsOf(String campaignRef) {
        return convocatorias.findByCampaignRef(campaignRef).map(this::facts);
    }

    private CampaignFundingFacts facts(Convocatoria c) {
        ConvocatoriaConfiguration config = c.getConfiguration();
        boolean monetary = config.acceptsMonetary();
        Long cleared = monetary
                ? ledgers.findByCampaignRef(c.getCampaignRef()).map(CampaignFundingLedger::clearedAmount).orElse(0L)
                : null;
        return new CampaignFundingFacts(c.getCampaignRef(), c.getOrganizationRef(), c.getStatus().name(),
                monetary ? config.currency() : null, monetary ? config.targetAmount() : null,
                monetary ? config.targetPolicy().name() : null, cleared, clock.instant());
    }
}
