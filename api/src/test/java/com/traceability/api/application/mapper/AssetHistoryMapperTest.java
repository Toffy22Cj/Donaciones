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
        AssetTransitionReadModel transition = new AssetTransitionReadModel(
                "AssetRegisteredEvent",
                "OK",
                "REGISTERED",
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
        assertEquals("OK", tDto.status());
    }

    @Test
    void toDto_withNullDocument_shouldReturnEmptyHistory() {
        AssetHistoryPublicDTO dto = assetHistoryMapper.toDto(null);
        assertTrue(dto.history().isEmpty());
    }
}
