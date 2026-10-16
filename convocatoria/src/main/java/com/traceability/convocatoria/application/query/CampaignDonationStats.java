package com.traceability.convocatoria.application.query;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Estadística de las donaciones de una convocatoria, solo para su organización: totales por día (monto y número de
 * donaciones), sin datos del donante.
 * <p>
 * Cuenta las donaciones con fondos aplicados ({@code fundsAppliedAt}), las mismas que suma el {@code clearedAmount}
 * del detalle de la causa, así que {@code totalAmount} coincide con "lo recaudado". Solo aparecen los días con
 * donaciones, en orden.
 */
public record CampaignDonationStats(String currency, Long targetAmount, long totalAmount, int donations, List<Day> days) {

    /** Un día (en la zona pedida) con su monto y número de donaciones. */
    public record Day(LocalDate date, long amount, int donations) {}

    /** Una donación con fondos aplicados: solo el instante y el monto. */
    public record AppliedDonation(Instant appliedAt, long amount) {}

    public static CampaignDonationStats of(String currency, Long targetAmount, List<AppliedDonation> applied, ZoneId zone) {
        Map<LocalDate, long[]> byDay = new TreeMap<>();
        long total = 0;
        for (AppliedDonation d : applied) {
            long[] day = byDay.computeIfAbsent(LocalDate.ofInstant(d.appliedAt(), zone), k -> new long[2]);
            day[0] += d.amount();
            day[1]++;
            total += d.amount();
        }
        List<Day> days = new ArrayList<>();
        byDay.forEach((date, v) -> days.add(new Day(date, v[0], (int) v[1])));
        return new CampaignDonationStats(currency, targetAmount, total, applied.size(), List.copyOf(days));
    }
}
