package com.traceability.core.domain.fund;

import com.traceability.core.domain.physicalasset.SplitChildIds;

import java.util.UUID;

/**
 * Identificador de una asignación pedida por HTTP (plan P1.1, DD-29): UUID v5 de {@code fundId + ":" + commandId} en
 * un espacio de nombres propio. El mismo comando sobre el mismo fondo da la misma asignación, sin guardar nada.
 */
public final class AllocationIds {

    /** Espacio de nombres de las asignaciones. Constante: cambiarlo cambiaría los ids de los reenvíos. */
    public static final UUID NS_ALLOCATION = UUID.fromString("3d9a7c21-58e4-5b6f-a0c2-7e14d9b38f05");

    private AllocationIds() {}

    public static String of(String fundId, String commandId) {
        return SplitChildIds.v5(NS_ALLOCATION, fundId + ":" + commandId).toString();
    }
}
