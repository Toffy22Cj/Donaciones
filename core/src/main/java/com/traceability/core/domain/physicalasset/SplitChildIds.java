package com.traceability.core.domain.physicalasset;

import java.util.UUID;

/** Skeleton B1-bis. */
public final class SplitChildIds {

    public static final UUID NS_ASSET_SPLIT = UUID.fromString("6f1c2a5e-9b47-5d3a-8e21-4c7b9d0f1a36");

    private SplitChildIds() {}

    public static String of(String parentAssetId, String commandId) {
        return UUID.randomUUID().toString();
    }

    public static UUID v5(UUID namespace, String name) {
        return UUID.randomUUID();
    }
}
