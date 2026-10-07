package com.traceability.convocatoria.application.query;

import com.traceability.convocatoria.application.port.out.CampaignFundingLedgerRepositoryPort;
import com.traceability.convocatoria.application.port.out.ConvocatoriaRepositoryPort;
import com.traceability.convocatoria.application.service.ConvocatoriaLifecycleService;
import com.traceability.convocatoria.domain.model.CampaignFundingLedger;
import com.traceability.convocatoria.domain.model.Convocatoria;
import com.traceability.convocatoria.domain.model.ConvocatoriaConfiguration;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Compone {@link Convocatoria} y, si acepta {@code MONETARY}, {@link CampaignFundingLedger} (plan B6-a §2.3;
 * {@code propuesta-apis-fase6.md:59}). Las {@code PRIVATE_LINK} y las {@code CLOSED} se devuelven: el código hace de
 * secreto (A6) y el estado se muestra.
 */
@Service
public class PublicCampaignQueryService implements ConvocatoriaReadPort {

    private final ConvocatoriaRepositoryPort convocatorias;
    private final CampaignFundingLedgerRepositoryPort ledgers;

    public PublicCampaignQueryService(ConvocatoriaRepositoryPort convocatorias, CampaignFundingLedgerRepositoryPort ledgers) {
        this.convocatorias = convocatorias;
        this.ledgers = ledgers;
    }

    @Override
    public Optional<PublicCampaignView> findPublicByCode(String publicCode) {
        if (publicCode == null || !ConvocatoriaLifecycleService.PUBLIC_CODE_FORMAT.matcher(publicCode).matches()) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private PublicCampaignView view(Convocatoria c) {
        ConvocatoriaConfiguration config = c.getConfiguration();
        boolean monetary = config.acceptsMonetary();
        Long cleared = monetary
                ? ledgers.findByCampaignRef(c.getCampaignRef()).map(CampaignFundingLedger::clearedAmount).orElse(0L)
                : null;
        return new PublicCampaignView(c.getOrganizationRef(), c.getTitle(), c.getDescription(), c.getStatus().name(),
                c.getStartDate(), c.getEndDate(), names(config.acceptedDonationTypes()),
                monetary ? names(config.acceptedPaymentMethods()) : null,
                monetary ? config.currency() : null, monetary ? config.targetAmount() : null, cleared);
    }

    private static Set<String> names(Set<? extends Enum<?>> values) {
        return values.stream().map(Enum::name).collect(Collectors.toCollection(TreeSet::new));
    }
}
