package com.traceability.api.application.mapper;

import com.traceability.api.application.dto.AssetHistoryPublicDTO;
import com.traceability.api.application.dto.PublicCustodianCategory;
import com.traceability.core.application.service.LocationReferenceService;
import com.traceability.core.application.port.out.AssetHistoryReadModel;
import com.traceability.core.application.port.out.AssetTransitionReadModel;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class AssetHistoryMapperTest {

    private LocationReferenceService locationReferenceService;
    private PublicDonationMapper publicDonationMapper;
    private AssetHistoryMapper assetHistoryMapper;

    @BeforeEach
    void setUp() {
        locationReferenceService = Mockito.mock(LocationReferenceService.class);
        publicDonationMapper = Mockito.mock(PublicDonationMapper.class);
        assetHistoryMapper = new AssetHistoryMapper(locationReferenceService, publicDonationMapper);
    }

    @Test
    void shouldMapCompleteDocument() {
        // Como lo escribe la proyección real (DonationProjectionHandler.appendAssetHistory): status = estado del
        // ciclo de vida, custodian = referencia del custodio. El fixture anterior los tenía intercambiados (H-B6D-2)
        AssetTransitionReadModel transition = new AssetTransitionReadModel(
                "AssetRegisteredEvent",
                "REGISTERED",
                "cust-1",
                "LOC-1",
                Instant.parse("2026-09-06T10:00:00Z")
        );

        AssetHistoryReadModel document = new AssetHistoryReadModel(
                "asset-001",
                List.of(transition)
        );

        when(locationReferenceService.resolveZone("LOC-1")).thenReturn(Optional.of("Zone A"));
        when(publicDonationMapper.mapCustodian("REGISTERED")).thenReturn(PublicCustodianCategory.UNCATEGORIZED);

        AssetHistoryPublicDTO dto = assetHistoryMapper.toDto(document);

        assertNotNull(dto);
        assertEquals(1, dto.history().size());
        
        var tDto = dto.history().get(0);
        assertEquals("AssetRegisteredEvent", tDto.eventType());
        assertEquals("2026-09-06T10:00:00Z", tDto.timestamp());
        assertEquals("Zone A", tDto.locationZone());
        assertEquals(PublicCustodianCategory.UNCATEGORIZED, tDto.custodianCategory());
        assertEquals("REGISTERED", tDto.status());
    }

    @Test
    void transitionsThatAreNotLifecycleStates_andMissingCustodians_areUncategorized_neverAnError() {
        when(locationReferenceService.resolveZone(Mockito.any())).thenReturn(Optional.empty());
        for (String status : List.of("SPLIT", "SPLIT_COMPENSATED", "CUSTODY_TRANSFERRED")) {
            AssetHistoryPublicDTO dto = assetHistoryMapper.toDto(new AssetHistoryReadModel("asset-002", List.of(
                    new AssetTransitionReadModel("ASSET_" + status, status, null, null, Instant.parse("2026-10-07T10:00:00Z")))));

            assertEquals(PublicCustodianCategory.UNCATEGORIZED, dto.history().get(0).custodianCategory());
        }
        Mockito.verify(publicDonationMapper, Mockito.never()).mapCustodian("SPLIT");
    }

    @Test
    void toDto_withNullDocument_shouldReturnEmptyHistory() {
        AssetHistoryPublicDTO dto = assetHistoryMapper.toDto(null);
        assertTrue(dto.history().isEmpty());
    }
}
