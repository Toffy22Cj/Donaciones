package com.traceability.core.application.security;

import com.traceability.core.infrastructure.projection.mongo.documents.AssetIndexDocument;
import com.traceability.core.infrastructure.projection.mongo.repositories.AssetIndexRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class AssetAuthorizationServiceTest {

    private AssetIndexRepository assetIndexRepository;
    private AssetAuthorizationService assetAuthorizationService;

    @BeforeEach
    void setUp() {
        assetIndexRepository = Mockito.mock(AssetIndexRepository.class);
        assetAuthorizationService = new AssetAuthorizationService(assetIndexRepository);
    }

    @Test
    void givenAssetBelongsToFund_whenAssetBelongsToFund_thenReturnsTrue() {
        String assetId = "asset-123";
        String fundId = "fund-abc";

        AssetIndexDocument doc = AssetIndexDocument.builder()
                .assetId(assetId)
                .projectionId(fundId) // projectionId is the fundId
                .build();

        when(assetIndexRepository.findById(assetId)).thenReturn(Optional.of(doc));

        boolean result = assetAuthorizationService.assetBelongsToFund(assetId, fundId);
        assertTrue(result, "Expected to return true when asset belongs to the correct fund");
    }

    @Test
    void givenAssetBelongsToDifferentFund_whenAssetBelongsToFund_thenReturnsFalse() {
        String assetId = "asset-123";
        String targetFundId = "fund-abc";
        String actualFundId = "fund-xyz";

        AssetIndexDocument doc = AssetIndexDocument.builder()
                .assetId(assetId)
                .projectionId(actualFundId)
                .build();

        when(assetIndexRepository.findById(assetId)).thenReturn(Optional.of(doc));

        boolean result = assetAuthorizationService.assetBelongsToFund(assetId, targetFundId);
        assertFalse(result, "Expected to return false when asset belongs to a different fund");
    }

    @Test
    void givenAssetDoesNotExist_whenAssetBelongsToFund_thenReturnsFalseWithoutExceptions() {
        String assetId = "asset-missing";
        String fundId = "fund-abc";

        when(assetIndexRepository.findById(assetId)).thenReturn(Optional.empty());

        boolean result = assetAuthorizationService.assetBelongsToFund(assetId, fundId);
        assertFalse(result, "Expected to return false when asset does not exist in the index");
    }

    @Test
    void givenNullParameters_whenAssetBelongsToFund_thenReturnsFalse() {
        assertFalse(assetAuthorizationService.assetBelongsToFund(null, "fund-1"));
        assertFalse(assetAuthorizationService.assetBelongsToFund("asset-1", null));
        assertFalse(assetAuthorizationService.assetBelongsToFund(null, null));
    }
}
