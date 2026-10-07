package com.traceability.core.application.saga;

/** Resultado guardado en el reclamo {@code SPLIT_RESOLUTION:{childAssetId}} (plan B1-bis §2). */
public enum SplitResolution {
    CHILD_CREATED,
    COMPENSATED,
    RESOLVED_MANUALLY;

    public static String claimKey(String childAssetId) {
        return "SPLIT_RESOLUTION:" + childAssetId;
    }
}
