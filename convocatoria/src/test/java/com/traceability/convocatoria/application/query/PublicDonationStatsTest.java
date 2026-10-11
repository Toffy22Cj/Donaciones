package com.traceability.convocatoria.application.query;

import com.traceability.convocatoria.application.query.PublicDonationStats.AppliedDonation;
import com.traceability.convocatoria.application.query.PublicDonationStats.Period;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PublicDonationStatsTest {

    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");

    private static AppliedDonation at(String instant, long amount) {
        return new AppliedDonation(Instant.parse(instant), amount);
    }

    private static LocalDate d(String date) {
        return LocalDate.parse(date);
    }

    @Test
    void sinDonaciones_niPeriodosNiTotales() {
        PublicDonationStats s = PublicDonationStats.of("COP", 1_000_000L, List.of(), BOGOTA);
        assertThat(s.totalAmount()).isZero();
        assertThat(s.donations()).isZero();
        assertThat(s.periods()).isEmpty();
    }

    @Test
    void conMenosDelMinimoEnTotal_hayTotalesPeroNoPeriodos() {
        PublicDonationStats s = PublicDonationStats.of("COP", 1_000_000L,
                List.of(at("2026-10-01T15:00:00Z", 100), at("2026-10-02T15:00:00Z", 200)), BOGOTA);
        assertThat(s.totalAmount()).isEqualTo(300);
        assertThat(s.donations()).isEqualTo(2);
        assertThat(s.periods()).isEmpty();
    }

    @Test
    void unDiaConElMinimo_esUnPeriodoDeUnDia() {
        PublicDonationStats s = PublicDonationStats.of("COP", null, List.of(
                at("2026-10-01T13:00:00Z", 100), at("2026-10-01T14:00:00Z", 200), at("2026-10-01T15:00:00Z", 300)),
                BOGOTA);
        assertThat(s.periods()).containsExactly(new Period(d("2026-10-01"), d("2026-10-01"), 600, 3));
    }

    @Test
    void losDiasConPocasDonaciones_seJuntanConLosSiguientes() {
        PublicDonationStats s = PublicDonationStats.of("COP", null, List.of(
                at("2026-10-01T15:00:00Z", 100),
                at("2026-10-03T15:00:00Z", 200), at("2026-10-03T16:00:00Z", 300),
                at("2026-10-04T15:00:00Z", 10), at("2026-10-04T16:00:00Z", 20), at("2026-10-04T17:00:00Z", 30)),
                BOGOTA);
        assertThat(s.periods()).containsExactly(
                new Period(d("2026-10-01"), d("2026-10-03"), 600, 3),
                new Period(d("2026-10-04"), d("2026-10-04"), 60, 3));
        assertThat(s.totalAmount()).isEqualTo(660);
    }

    @Test
    void loQueSobraAlFinal_seSumaAlUltimoPeriodo() {
        PublicDonationStats s = PublicDonationStats.of("COP", null, List.of(
                at("2026-10-01T15:00:00Z", 100), at("2026-10-01T16:00:00Z", 100), at("2026-10-01T17:00:00Z", 100),
                at("2026-10-05T15:00:00Z", 50)),
                BOGOTA);
        assertThat(s.periods()).containsExactly(new Period(d("2026-10-01"), d("2026-10-05"), 350, 4));
        assertThat(s.periods().stream().mapToLong(Period::amount).sum()).isEqualTo(s.totalAmount());
    }

    @Test
    void elDiaSeCuentaEnLaZonaPedida() {
        // 2026-10-02T03:00Z son las 22:00 del 1 de octubre en Bogotá
        PublicDonationStats s = PublicDonationStats.of("COP", null, List.of(
                at("2026-10-02T03:00:00Z", 1), at("2026-10-02T03:30:00Z", 1), at("2026-10-02T04:00:00Z", 1)),
                BOGOTA);
        assertThat(s.periods()).containsExactly(new Period(d("2026-10-01"), d("2026-10-01"), 3, 3));
    }

    @Test
    void elOrdenDeEntradaNoImporta() {
        List<AppliedDonation> ordered = List.of(at("2026-10-01T15:00:00Z", 1), at("2026-10-01T16:00:00Z", 2),
                at("2026-10-01T17:00:00Z", 3), at("2026-10-02T15:00:00Z", 4), at("2026-10-02T16:00:00Z", 5),
                at("2026-10-02T17:00:00Z", 6));
        List<AppliedDonation> shuffled = List.of(ordered.get(4), ordered.get(1), ordered.get(5), ordered.get(0),
                ordered.get(3), ordered.get(2));
        assertThat(PublicDonationStats.of("COP", null, shuffled, BOGOTA))
                .isEqualTo(PublicDonationStats.of("COP", null, ordered, BOGOTA));
    }
}
