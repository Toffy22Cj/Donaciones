package com.traceability.core.domain.physicalasset;

import java.util.UUID;

/**
 * Identificador de un activo registrado (Q9 de D-API, Carlos, 2026-10-07): UUID v5 de
 * {@code organizationRef + ":" + commandId} en un espacio de nombres propio, distinto del de la división. El mismo
 * comando de la misma organización da el mismo activo sin guardar nada; dos organizaciones con el mismo
 * {@code commandId} dan activos distintos.
 */
public final class AssetIds {

    /** Espacio de nombres de los registros. Constante: cambiarlo cambiaría los ids de los registros repetidos. */
    public static final UUID NS_ASSET = UUID.fromString("0b8e4f6a-3c21-5d7e-9a14-2f6c8b1d5e90");

    private AssetIds() {}

    public static String of(String organizationRef, String commandId) {
        return SplitChildIds.v5(NS_ASSET, organizationRef + ":" + commandId).toString();
    }
}
