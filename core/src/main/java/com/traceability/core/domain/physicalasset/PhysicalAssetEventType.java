package com.traceability.core.domain.physicalasset;

import com.traceability.core.domain.event.EventType;

/**
 * Specific event types for PhysicalAsset aggregate.
 */
public enum PhysicalAssetEventType implements EventType {
    ASSET_REGISTERED,
    ASSET_DISPATCHED,
    ASSET_RECEIVED,
    ASSET_CUSTODY_TRANSFERRED,
    ASSET_SPLIT,
    ASSET_DEPLETED,
    ASSET_SPLIT_COMPENSATED,
    ASSET_DELIVERED;
}
