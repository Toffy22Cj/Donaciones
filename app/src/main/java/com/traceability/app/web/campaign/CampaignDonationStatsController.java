package com.traceability.app.web.campaign;

import com.traceability.api.web.CurrentActor;
import com.traceability.app.application.campaign.CampaignDonationStatsUseCase;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.convocatoria.application.query.CampaignDonationStats;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.ZoneId;
import java.util.List;

/**
 * Estadística de donaciones de una convocatoria, para el gráfico de su organización. Mismo acceso que la predicción
 * ({@link CampaignDonationStatsUseCase}): nunca pública. Totales por día (monto y número de donaciones), sin datos del
 * donante. Los días se cuentan en {@code traceability.public-stats.time-zone}.
 */
@RestController
public class CampaignDonationStatsController {

    public record DayResponse(String date, String amount, int donations) {}

    public record DonationStatsResponse(String currency, String targetAmount, String totalAmount, int donations,
                                        String timeZone, List<DayResponse> days, String asOf) {}

    private final CampaignDonationStatsUseCase stats;
    private final ZoneId zone;
    private final Clock clock;

    public CampaignDonationStatsController(CampaignDonationStatsUseCase stats,
                                           @Value("${traceability.public-stats.time-zone:America/Bogota}") String zone,
                                           ObjectProvider<Clock> clock) {
        this.stats = stats;
        this.zone = ZoneId.of(zone);
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    @GetMapping("/api/v1/organizations/{organizationId}/campaigns/{campaignRef}/donation-stats")
    public ResponseEntity<DonationStatsResponse> donationStats(@CurrentActor AuthorizationPrincipal principal,
                                                               @PathVariable("organizationId") String organizationId,
                                                               @PathVariable("campaignRef") String campaignRef) {
        CampaignDonationStats s = stats.stats(principal, organizationId, campaignRef, zone);
        List<DayResponse> days = s.days().stream()
                .map(d -> new DayResponse(d.date().toString(), Long.toString(d.amount()), d.donations()))
                .toList();
        DonationStatsResponse body = new DonationStatsResponse(s.currency(),
                s.targetAmount() == null ? null : s.targetAmount().toString(), Long.toString(s.totalAmount()),
                s.donations(), zone.getId(), days, clock.instant().toString());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
