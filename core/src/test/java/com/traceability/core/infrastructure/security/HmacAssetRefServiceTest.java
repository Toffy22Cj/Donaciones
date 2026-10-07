package com.traceability.core.infrastructure.security;

import com.traceability.core.application.security.AssetRefService;
import com.traceability.core.infrastructure.security.properties.TrackingSecurityProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HmacAssetRefServiceTest {

    private AssetRefService assetRefService;

    @BeforeEach
    void setUp() {
        TrackingSecurityProperties properties = new TrackingSecurityProperties();
        properties.setAssetRefSecret("test-secret-asset-ref-key-12345678901234567890123456789012");
        properties.setTrackingCodeSecret("test-secret-tracking-key-12345678901234567890123456789012");
        
        assetRefService = new HmacAssetRefService(properties);
    }

    @Test
    void shouldBeDeterministicAndNotColide() {
        String assetId1 = "asset-001";
        String assetId2 = "asset-002";

        String ref1FirstTime = assetRefService.computeRef(assetId1);
        String ref1SecondTime = assetRefService.computeRef(assetId1);
        String ref2 = assetRefService.computeRef(assetId2);

        assertNotNull(ref1FirstTime);
        assertEquals(ref1FirstTime, ref1SecondTime, "computeRef must be deterministic");
        assertNotEquals(ref1FirstTime, ref2, "Different assetIds must produce different assetRefs");
        
        // Assert Base64URL without padding format
        assertTrue(ref1FirstTime.matches("^[A-Za-z0-9_-]+$"), "Must be valid Base64URL without padding");
        assertFalse(ref1FirstTime.endsWith("="), "Must not have padding");
    }

    @Test
    void shouldResolveAssetIdFromValidCandidates() {
        String targetAssetId = "asset-001";
        String assetRef = assetRefService.computeRef(targetAssetId);
        
        List<String> candidates = List.of("asset-999", "asset-002", targetAssetId, "asset-003");
        
        Optional<String> resolved = assetRefService.resolveAssetId(assetRef, candidates);
        
        assertTrue(resolved.isPresent());
        assertEquals(targetAssetId, resolved.get());
    }

    @Test
    void shouldReturnEmptyWhenNoMatchFound() {
        String targetAssetId = "asset-001";
        String assetRef = assetRefService.computeRef(targetAssetId);
        
        List<String> candidates = List.of("asset-999", "asset-002", "asset-003");
        
        Optional<String> resolved = assetRefService.resolveAssetId(assetRef, candidates);
        
        assertTrue(resolved.isEmpty());
    }

    @Test
    void shouldHandleNullOrEmptyInputsGracefully() {
        assertTrue(assetRefService.resolveAssetId(null, List.of("candidate")).isEmpty());
        assertTrue(assetRefService.resolveAssetId("ref", null).isEmpty());
        assertTrue(assetRefService.resolveAssetId("ref", List.of()).isEmpty());
        
        assertThrows(IllegalArgumentException.class, () -> assetRefService.computeRef(null));
        assertThrows(IllegalArgumentException.class, () -> assetRefService.computeRef(""));
    }
}
