package com.traceability.core.domain.physicalasset;

/**
 * Lifecycle states for a PhysicalAsset.
 * Ref: ADR-005, ADR-006, ADR-014
 */
public enum AssetLifecycleStatus {
    REGISTERED,
    DISPATCHED,
    RECEIVED,
    DELIVERED,
    DEPLETED
}
