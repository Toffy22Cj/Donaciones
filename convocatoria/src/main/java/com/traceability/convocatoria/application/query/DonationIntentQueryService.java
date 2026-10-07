package com.traceability.convocatoria.application.query;

import com.traceability.convocatoria.application.port.out.ConvocatoriaRepositoryPort;
import com.traceability.convocatoria.application.port.out.DonationIntentRepositoryPort;
import com.traceability.convocatoria.domain.model.Convocatoria;
import com.traceability.convocatoria.domain.model.DonationIntent;
import com.traceability.convocatoria.domain.model.StatusTokens;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

@Service
public class DonationIntentQueryService implements DonationIntentReadPort {

    private final DonationIntentRepositoryPort donationIntents;
    private final ConvocatoriaRepositoryPort convocatorias;
    private final Clock clock;

    public DonationIntentQueryService(DonationIntentRepositoryPort donationIntents,
                                      ConvocatoriaRepositoryPort convocatorias, ObjectProvider<Clock> clock) {
        this.donationIntents = donationIntents;
        this.convocatorias = convocatorias;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    @Override
    public Optional<IntentStatusView> findForStatusToken(String intentId, String statusToken) {
        return Optional.empty();
    }

    @Override
    public List<DonationView> findByDonorRef(String donorRef, int limit) {
        return List.of();
    }

    private DonationView view(DonationIntent i) {
        String title = convocatorias.findByCampaignRef(i.getCampaignRef()).map(Convocatoria::getTitle).orElse(null);
        return new DonationView(i.getIntentId(), title, i.getAmount(), i.getCurrency(), i.getStatus().name(),
                i.getFundId(), i.getApplicationTracking().fundsAppliedAt());
    }
}
