package com.traceability.core.application.port.out;

import com.traceability.core.domain.event.HumanActor;

import java.time.YearMonth;
import java.util.List;

/**
 * Recaudado por mes de una organización (ficha R-6, G-6; D-R6-3, D-R6-4; plan del Bloque B, P-R6B-3, P-R6B-4 y
 * P-R6B-6). La misma política que {@link FundOperationalReadPort} (DD-31): {@code ADMINISTRATOR} o {@code EMPLOYEE}
 * de esa organización. Los {@code FUNDS_CLEARED} (1.0 y 2.0) de los fondos cuya génesis lleva la organización,
 * agrupados por el mes de {@code occurredAt} en {@code America/Bogota}. Un ítem por mes y moneda de los fondos de la
 * organización, con los meses vacíos en 0; sin fondos, sin ítems. Sin datos del donante.
 */
public interface FundMonthlyClearingReadPort {

    /** Zona del corte de mes (D-R6-4). */
    String TIMEZONE = "America/Bogota";

    /** @param months de 1 a 12: el mes en curso y los {@code months - 1} anteriores (lo valida quien llama) */
    MonthlyClearing monthlyForOrganization(String organizationRef, int months, HumanActor actor);

    /** {@code truncated}: la organización tiene más fondos de los que se leen (P-R6B-6). */
    record MonthlyClearing(String timezone, List<MonthItem> items, boolean truncated) {}

    record MonthItem(YearMonth month, String currency, long clearedAmount) {}
}
