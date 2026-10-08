package com.traceability.convocatoria.domain.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S-05 (Carlos, 2026-10-07): los importes son enteros en unidades mínimas de la moneda (Q-CV01-3), y el exponente lo
 * da ISO 4217. Para COP es 2: un importe de {@code 100000} son 1 000,00 COP. Este test fija ese exponente; si la tabla
 * de la JVM cambiara, la interpretación de todos los importes (y el predictor, H-P3-1) habría que revisarla.
 */
class CurrencyMinorUnitsTest {

    @Test
    void copHasExponentTwo_inIso4217() {
        assertThat(Currency.getInstance("COP").getDefaultFractionDigits()).isEqualTo(2);
    }

    @Test
    void anAmountInMinorUnits_readsAsPesosWithTwoDecimals() {
        long minorUnits = 100_000L;
        int exponent = Currency.getInstance("COP").getDefaultFractionDigits();

        assertThat(BigDecimal.valueOf(minorUnits, exponent)).isEqualByComparingTo("1000.00");
    }

    @Test
    void aCampaignInCop_isAccepted() {
        ConvocatoriaConfiguration config = new ConvocatoriaConfiguration(java.util.Set.of(DonationType.MONETARY),
                java.util.Set.of(PaymentMethod.GATEWAY), "COP", 500_000_000L, TargetPolicy.FLEXIBLE, null);

        assertThat(config.currency()).isEqualTo("COP");
        assertThat(BigDecimal.valueOf(config.targetAmount(), Currency.getInstance(config.currency()).getDefaultFractionDigits()))
                .as("meta de 5 000 000,00 COP").isEqualByComparingTo("5000000.00");
    }
}
