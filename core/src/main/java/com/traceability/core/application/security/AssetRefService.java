package com.traceability.core.application.security;

import java.util.Collection;
import java.util.Optional;

/**
 * Service to compute and resolve deterministic obfuscated references (assetRef) 
 * for PhysicalAssets without storing them persistently, as per ADR-021-D.
 */
public interface AssetRefService {

    /**
     * Computes the deterministic assetRef for a given assetId.
     * @param assetId the internal physical asset identifier
     * @return the Base64URL encoded HMAC-SHA-256 of the assetId
     */
    String computeRef(String assetId);

    /**
     * Resolves an assetRef back to its original assetId by computing the assetRef
     * for each candidate and matching it against the provided assetRef in a timing-safe manner.
     * @param assetRef the obfuscated reference to search for
     * @param candidateAssetIds the list of known assetIds to test
     * @return an Optional containing the matched assetId, or empty if none matches
     */
    Optional<String> resolveAssetId(String assetRef, Collection<String> candidateAssetIds);
}
