package com.traceability.convocatoria.application.query;

import com.traceability.convocatoria.application.port.out.ConvocatoriaRepositoryPort;
import com.traceability.convocatoria.application.port.out.DonationIntentRepositoryPort;
import com.traceability.convocatoria.domain.model.Convocatoria;
import com.traceability.convocatoria.domain.model.DonationIntent;
import com.traceability.convocatoria.domain.model.StatusTokens;
import com.traceability.convocatoria.domain.model.Visibility;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
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
        if (intentId == null || statusToken == null) {
            return Optional.empty();
        }
        return donationIntents.findById(intentId)
                .filter(i -> StatusTokens.matches(statusToken, i.getAccess().statusTokenHash()))
                .filter(i -> clock.instant().isBefore(i.getAccess().statusTokenExpiresAt()))
                .map(i -> new IntentStatusView(i.getIntentId(), i.getStatus().name(), i.getFundId(),
                        i.getApplicationTracking().fundsAppliedAt()));
    }

    @Override
    public List<DonationView> findByDonorRef(String donorRef, int limit) {
        return donationIntents.findByDonorRef(donorRef, limit).stream().map(this::view).toList();
    }

    /** Una sola lectura de la convocatoria por intención, como antes: de ella salen el título y el {@code publicCode}. */
    private DonationView view(DonationIntent i) {
        Optional<Convocatoria> campaign = convocatorias.findByCampaignRef(i.getCampaignRef());
        String title = campaign.map(Convocatoria::getTitle).orElse(null);
        // E5-2: el filtro va aquí, dentro de convocatoria, con la visibilidad actual de la convocatoria
        String publicCode = campaign.filter(c -> c.getVisibility() == Visibility.PUBLIC)
                .map(Convocatoria::getPublicCode).orElse(null);
        Instant confirmedAt = i.getConfirmation() == null ? null : i.getConfirmation().confirmedAt();
        return new DonationView(i.getIntentId(), title, i.getAmount(), i.getCurrency(), i.getStatus().name(),
                i.getFundId(), i.getApplicationTracking().fundsAppliedAt(), i.getCreatedAt(), confirmedAt,
                i.getOrganizationRef(), i.getPaymentMethod().name(), publicCode, i.getCampaignRef());
    }
}
