package com.traceability.core.application.port.out;

/**
 * Port used by the API layer to authorize access to assets.
 */
public interface AssetAuthorizationPort {
    /**
     * Verifies if an asset belongs to a specific fund.
     *
     * @param assetId the internal asset ID to check
     * @param fundId  the fund ID that the asset is expected to belong to
     * @return true if the asset exists and belongs to the fund, false otherwise
     */
    boolean assetBelongsToFund(String assetId, String fundId);
}
