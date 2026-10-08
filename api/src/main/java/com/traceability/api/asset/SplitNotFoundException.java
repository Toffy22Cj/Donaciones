package com.traceability.api.asset;

import com.traceability.core.domain.shared.exceptions.AggregateNotFoundException;

/**
 * El padre, de la organización del actor, no tiene una división con ese hijo → 404 (matriz §4). Solo se llega aquí
 * después de autorizar sobre el padre, así que no revela nada de otra organización.
 */
public class SplitNotFoundException extends AggregateNotFoundException {
    public SplitNotFoundException(String childAssetId) {
        super("split child " + childAssetId);
    }
}
