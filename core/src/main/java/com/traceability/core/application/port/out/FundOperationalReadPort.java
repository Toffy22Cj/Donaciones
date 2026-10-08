package com.traceability.core.application.port.out;

import com.traceability.core.domain.event.HumanActor;

import java.util.List;

/**
 * Fondos de la organización para su personal (plan P1.1): lo necesario para pedir una asignación y registrar un
 * activo del Camino A. Exige {@code ADMINISTRATOR} o {@code EMPLOYEE} de esa organización. Sin datos del donante.
 */
public interface FundOperationalReadPort {

    List<FundView> listForOrganization(String organizationRef, HumanActor actor);

    record FundView(String fundId, String campaignRef, String currency, long clearedAmount, long availableAmount,
                    List<AllocationItem> allocations) {}

    record AllocationItem(String allocationId, long amount, String status) {}
}
