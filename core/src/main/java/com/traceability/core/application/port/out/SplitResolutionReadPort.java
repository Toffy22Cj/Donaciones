package com.traceability.core.application.port.out;

import com.traceability.core.application.saga.SplitResolutionStatus;

import java.util.Optional;

/** Estado de una división (D-SPLIT S7). Vacío si el padre no tiene un {@code ASSET_SPLIT} con ese hijo. */
public interface SplitResolutionReadPort {

    Optional<SplitResolutionStatus> findStatus(String parentAssetId, String childAssetId);
}
