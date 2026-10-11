package com.traceability.convocatoria.application.query;

import com.traceability.convocatoria.application.query.PublicDonationStats.AppliedDonation;
import com.traceability.convocatoria.application.query.PublicDonationStats.Day;
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
    void sinDonaciones_niDiasNiTotales() {
        PublicDonationStats s = PublicDonationStats.of("COP", 1_000_000L, List.of(), BOGOTA);
        assertThat(s.totalAmount()).isZero();
        assertThat(s.donations()).isZero();
        assertThat(s.days()).isEmpty();
    }

    @Test
    void unaSolaDonacion_esUnDia() {
        PublicDonationStats s = PublicDonationStats.of("COP", 1_000_000L, List.of(at("2026-10-01T15:00:00Z", 100)), BOGOTA);
        assertThat(s.totalAmount()).isEqualTo(100);
        assertThat(s.donations()).isEqualTo(1);
        assertThat(s.days()).containsExactly(new Day(d("2026-10-01"), 100, 1));
    }

    @Test
    void sumaPorDia_enOrden_ySoloLosDiasConDonaciones() {
        PublicDonationStats s = PublicDonationStats.of("COP", null, List.of(
                at("2026-10-04T15:00:00Z", 10),
                at("2026-10-01T15:00:00Z", 100), at("2026-10-01T16:00:00Z", 200),
                at("2026-10-04T16:00:00Z", 20)),
                BOGOTA);
        assertThat(s.days()).containsExactly(
                new Day(d("2026-10-01"), 300, 2),
                new Day(d("2026-10-04"), 30, 2));
        assertThat(s.totalAmount()).isEqualTo(330);
        assertThat(s.donations()).isEqualTo(4);
    }

    @Test
    void elDiaSeCuentaEnLaZonaPedida() {
        // 2026-10-02T03:00Z son las 22:00 del 1 de octubre en Bogotá
        PublicDonationStats s = PublicDonationStats.of("COP", null, List.of(at("2026-10-02T03:00:00Z", 1)), BOGOTA);
        assertThat(s.days()).containsExactly(new Day(d("2026-10-01"), 1, 1));
    }
}
