package com.traceability.convocatoria.application.query;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Estadística pública de las donaciones de una convocatoria: solo números agregados por periodo, nunca donaciones
 * sueltas ni datos del donante.
 * <p>
 * Cuenta las donaciones con fondos aplicados ({@code fundsAppliedAt}), las mismas que suma el {@code clearedAmount}
 * del detalle público, así que {@code totalAmount} coincide con "lo recaudado". Cada periodo junta días seguidos hasta
 * reunir al menos {@link #MIN_DONATIONS_PER_PERIOD} donaciones, para que una barra no deje ver lo que dio una sola
 * persona; lo que sobra al final se suma al último periodo. Con menos de ese mínimo en total no hay periodos.
 */
public record PublicDonationStats(String currency, Long targetAmount, long totalAmount, int donations,
                                  List<Period> periods) {

    public static final int MIN_DONATIONS_PER_PERIOD = 3;

    /** Días {@code from}–{@code to} (ambos incluidos, en la zona pedida) con su monto y número de donaciones. */
    public record Period(LocalDate from, LocalDate to, long amount, int donations) {}

    /** Una donación con fondos aplicados: solo el instante y el monto. */
    public record AppliedDonation(Instant appliedAt, long amount) {}

    public static PublicDonationStats of(String currency, Long targetAmount, List<AppliedDonation> applied, ZoneId zone) {
        Map<LocalDate, long[]> byDay = new TreeMap<>();
        long total = 0;
        for (AppliedDonation d : applied.stream().sorted(Comparator.comparing(AppliedDonation::appliedAt)).toList()) {
            long[] day = byDay.computeIfAbsent(LocalDate.ofInstant(d.appliedAt(), zone), k -> new long[2]);
            day[0] += d.amount();
            day[1]++;
            total += d.amount();
        }
        return new PublicDonationStats(currency, targetAmount, total, applied.size(), periods(byDay));
    }

    private static List<Period> periods(Map<LocalDate, long[]> byDay) {
        List<Period> periods = new ArrayList<>();
        LocalDate from = null;
        LocalDate to = null;
        long amount = 0;
        int count = 0;
        for (Map.Entry<LocalDate, long[]> day : byDay.entrySet()) {
            if (from == null) from = day.getKey();
            to = day.getKey();
            amount += day.getValue()[0];
            count += (int) day.getValue()[1];
            if (count >= MIN_DONATIONS_PER_PERIOD) {
                periods.add(new Period(from, to, amount, count));
                from = null;
                amount = 0;
                count = 0;
            }
        }
        if (count > 0 && !periods.isEmpty()) {
            Period last = periods.remove(periods.size() - 1);
            periods.add(new Period(last.from(), to, last.amount() + amount, last.donations() + count));
        }
        return List.copyOf(periods);
    }
}
