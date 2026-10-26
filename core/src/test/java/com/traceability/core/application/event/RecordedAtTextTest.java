package com.traceability.core.application.event;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DT-B-1 (plan de las deudas 1 a 3 §2; P-DT1-2): el texto de listado de {@code recordedAt} tiene siempre 9 decimales,
 * en UTC y sin pérdida, para que el orden como texto sea el cronológico.
 */
class RecordedAtTextTest {

    @Test
    void dtb1_alwaysNineDecimals_inUtc() {
        assertThat(RecordedAtText.of(Instant.parse("2026-10-20T10:00:05Z"))).isEqualTo("2026-10-20T10:00:05.000000000Z");
        assertThat(RecordedAtText.of(Instant.parse("2026-10-20T10:00:05.1Z"))).isEqualTo("2026-10-20T10:00:05.100000000Z");
        assertThat(RecordedAtText.of(Instant.parse("2026-10-20T10:00:05.123456Z"))).isEqualTo("2026-10-20T10:00:05.123456000Z");
        assertThat(RecordedAtText.of(Instant.parse("2026-10-20T10:00:05.123456789Z"))).isEqualTo("2026-10-20T10:00:05.123456789Z");
        // cerca de la medianoche UTC: ninguna otra zona escribe este día y esta hora
        assertThat(RecordedAtText.of(Instant.parse("2026-10-20T23:59:59.5Z"))).isEqualTo("2026-10-20T23:59:59.500000000Z");
    }

    @Test
    void dtb1_withoutLoss_parsesBackToTheSameInstant() {
        for (String s : new String[] {"2026-10-20T10:00:05Z", "2026-10-20T10:00:05.1Z", "2026-10-20T10:00:05.123456Z",
                "2026-10-20T10:00:05.123456789Z"}) {
            Instant t = Instant.parse(s);
            assertThat(Instant.parse(RecordedAtText.of(t))).isEqualTo(t);
        }
    }

    // El hallazgo H-D-2: con Instant.toString(), el segundo exacto ordena detrás de su décima
    @Test
    void dtb1_withinTheSameSecond_theTextOrderIsTheChronologicalOne() {
        Instant exact = Instant.parse("2026-10-20T10:00:05Z");
        Instant later = Instant.parse("2026-10-20T10:00:05.1Z");

        assertThat(exact.toString().compareTo(later.toString())).as("la deuda").isPositive();
        assertThat(RecordedAtText.of(exact).compareTo(RecordedAtText.of(later))).isNegative();
    }
}
