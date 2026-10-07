package com.traceability.core.application.port.out;

import com.traceability.core.application.saga.SplitResolutionStatus;
import com.traceability.core.domain.event.HumanActor;

import java.util.Optional;

/**
 * Lecturas de activos para un empleado autenticado (plan B6-c §2.1; matriz §4 y §4b): exigen {@code EMPLOYEE} y la
 * frontera de organización. Un activo inexistente lanza
 * {@link com.traceability.core.application.exception.PhysicalAssetNotFoundException}.
 */
public interface PhysicalAssetOperationalReadPort {

    PhysicalAssetOperationalView findOperationalView(String assetId, HumanActor actor);

    /** Vacío si el padre (de la organización del actor) no tiene una división con ese hijo. */
    Optional<SplitResolutionStatus> findSplitStatus(String parentAssetId, String childAssetId, HumanActor actor);

    /**
     * Activos de la organización para el panel (P2.7): {@code ADMINISTRATOR} o {@code EMPLOYEE} de esa organización;
     * los mismos campos que {@link #findOperationalView}, sin {@code donorRef}. Una página con tope de 200.
     */
    java.util.List<PhysicalAssetOperationalView> listForOrganization(String organizationRef, HumanActor actor);
}
