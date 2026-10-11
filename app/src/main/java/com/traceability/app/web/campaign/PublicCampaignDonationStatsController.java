package com.traceability.app.web.campaign;

import com.traceability.convocatoria.application.query.PublicDonationStats;
import com.traceability.convocatoria.application.query.PublicDonationStatsQuery;
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
 * Estadística pública de donaciones de una convocatoria, para el gráfico del detalle de la causa. Ruta pública en
 * {@code PublicRoutes}, con el mismo acceso y el mismo 404 que CV-07. Solo totales por periodo (al menos
 * {@link PublicDonationStats#MIN_DONATIONS_PER_PERIOD} donaciones cada uno), sin donaciones sueltas ni datos del
 * donante. Los días se cuentan en {@code traceability.public-stats.time-zone}. El código no se registra ni se devuelve.
 */
@RestController
public class PublicCampaignDonationStatsController {

    public record PeriodResponse(String from, String to, String amount, int donations) {}

    public record DonationStatsResponse(String currency, String targetAmount, String totalAmount, int donations,
                                        int minDonationsPerPeriod, String timeZone, List<PeriodResponse> periods,
                                        String asOf) {}

    private final PublicDonationStatsQuery stats;
    private final ZoneId zone;
    private final Clock clock;

    public PublicCampaignDonationStatsController(PublicDonationStatsQuery stats,
                                                 @Value("${traceability.public-stats.time-zone:America/Bogota}") String zone,
                                                 ObjectProvider<Clock> clock) {
        this.stats = stats;
        this.zone = ZoneId.of(zone);
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    @GetMapping("/api/v1/public/campaigns/{publicCode}/donation-stats")
    public ResponseEntity<DonationStatsResponse> donationStats(@PathVariable("publicCode") String publicCode) {
        PublicDonationStats s = stats.statsOf(publicCode, zone).orElseThrow(PublicCampaignNotFoundException::new);
        List<PeriodResponse> periods = s.periods().stream()
                .map(p -> new PeriodResponse(p.from().toString(), p.to().toString(), Long.toString(p.amount()),
                        p.donations()))
                .toList();
        DonationStatsResponse body = new DonationStatsResponse(s.currency(),
                s.targetAmount() == null ? null : s.targetAmount().toString(), Long.toString(s.totalAmount()),
                s.donations(), PublicDonationStats.MIN_DONATIONS_PER_PERIOD, zone.getId(), periods,
                clock.instant().toString());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
