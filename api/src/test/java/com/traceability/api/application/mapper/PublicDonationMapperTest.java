package com.traceability.api.application.mapper;

import com.traceability.api.application.dto.PublicCustodianCategory;
import com.traceability.api.application.dto.PublicDonationStatus;
import com.traceability.api.application.dto.PublicDonationTrackingDTO;
import com.traceability.api.application.dto.PublicLogisticsItemDTO;
import com.traceability.contracts.campaign.CampaignPublicSummaryPort;
import com.traceability.contracts.campaign.CampaignPublicSummaryPort.CampaignPublicSummary;
import com.traceability.core.application.port.out.DonationReadModel;
import com.traceability.core.application.port.out.FundClearingDatesReadPort;
import com.traceability.core.application.port.out.FundClearingDatesReadPort.ClearingDates;
import com.traceability.core.application.port.out.LogisticsReadItem;
import com.traceability.core.application.security.AssetRefService;
import com.traceability.core.application.service.LocationReferenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublicDonationMapperTest {

    private AssetRefService assetRefService;
    private LocationReferenceService locationReferenceService;
    private CampaignPublicSummaryPort campaignSummaries;
    private FundClearingDatesReadPort clearingDates;
    private PublicDonationMapper mapper;

    @BeforeEach
    void setUp() {
        assetRefService = Mockito.mock(AssetRefService.class);
        locationReferenceService = Mockito.mock(LocationReferenceService.class);
        campaignSummaries = Mockito.mock(CampaignPublicSummaryPort.class);
        when(campaignSummaries.findByCampaignRef(anyString())).thenReturn(Optional.empty());
        clearingDates = Mockito.mock(FundClearingDatesReadPort.class);
        when(clearingDates.findClearingDates(anyString())).thenReturn(Optional.empty());
        mapper = new PublicDonationMapper(assetRefService, locationReferenceService, campaignSummaries, clearingDates);
    }

    @Test
    void shouldMapValidDonationWithNullLegacyFields() {
        DonationReadModel model = new DonationReadModel(
                "f1", null, null, 100L, 100L, 0L, 0L, 0L, "ACTIVE", List.of()
        );

        PublicDonationTrackingDTO dto = mapper.toPublicDTO(model);

        assertNull(dto.financialSnapshot().currency());
        assertNull(dto.campaignRef());
        assertEquals(PublicDonationStatus.ACTIVA, dto.status());
        assertEquals(100L, dto.financialSnapshot().originalAmount());
    }

    @Test
    void shouldMapValidDonationWithAllFields() {
        DonationReadModel model = new DonationReadModel(
                "f1", "USD", "camp-1", 100L, 100L, 0L, 0L, 0L, "PAUSED", List.of()
        );

        PublicDonationTrackingDTO dto = mapper.toPublicDTO(model);

        assertEquals("USD", dto.financialSnapshot().currency());
        assertEquals("camp-1", dto.campaignRef());
        assertEquals(PublicDonationStatus.EN_PROCESO, dto.status());
    }

    @Test
    void shouldThrowOnInvalidStatus() {
        DonationReadModel model = new DonationReadModel(
                "f1", "USD", "camp-1", 100L, 100L, 0L, 0L, 0L, "UNKNOWN_STATUS", List.of()
        );

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> mapper.toPublicDTO(model));
        assertEquals("Unexpected status in DonationReadModel: UNKNOWN_STATUS", ex.getMessage());
    }

    @Test
    void shouldMapLogisticsWithKnownLocation() {
        when(assetRefService.computeRef("asset-1")).thenReturn("ref-1");
        when(locationReferenceService.resolveZone("loc-1")).thenReturn(Optional.of("Zone A"));

        LogisticsReadItem item = new LogisticsReadItem(
                "asset-1", "DISPATCHED", "VACCINE", "DOSES", new BigDecimal("100"), "loc-1", "cust-1", null
        );
        DonationReadModel model = new DonationReadModel("f1", "USD", "camp-1", 100L, 100L, 0L, 0L, 0L, "ACTIVE", List.of(item));

        PublicDonationTrackingDTO dto = mapper.toPublicDTO(model);
        PublicLogisticsItemDTO itemDTO = dto.logistics().get(0);

        assertEquals("ref-1", itemDTO.assetRef());
        assertEquals("Zone A", itemDTO.locationZone());
        assertEquals(new BigDecimal("100"), itemDTO.quantity());
        assertEquals(PublicCustodianCategory.LOGISTICS_PARTNER, itemDTO.custodianCategory());
    }

    @Test
    void shouldMapLogisticsWithUnknownLocation() {
        when(assetRefService.computeRef("asset-1")).thenReturn("ref-1");
        when(locationReferenceService.resolveZone("loc-1")).thenReturn(Optional.empty());

        LogisticsReadItem item = new LogisticsReadItem(
                "asset-1", "DISPATCHED", "VACCINE", "DOSES", new BigDecimal("100"), "loc-1", "cust-1", null
        );
        DonationReadModel model = new DonationReadModel("f1", "USD", "camp-1", 100L, 100L, 0L, 0L, 0L, "ACTIVE", List.of(item));

        PublicDonationTrackingDTO dto = mapper.toPublicDTO(model);
        PublicLogisticsItemDTO itemDTO = dto.logistics().get(0);

        assertNull(itemDTO.locationZone());
    }

    @Test
    void shouldMapAllCustodianCategories() {
        when(assetRefService.computeRef(anyString())).thenReturn("ref-any");
        when(locationReferenceService.resolveZone(anyString())).thenReturn(Optional.empty());

        LogisticsReadItem registered = new LogisticsReadItem("a1", "REGISTERED", "t", "u", BigDecimal.ONE, "l", "c", null);
        LogisticsReadItem dispatched = new LogisticsReadItem("a2", "DISPATCHED", "t", "u", BigDecimal.ONE, "l", "c", null);
        LogisticsReadItem received = new LogisticsReadItem("a3", "RECEIVED", "t", "u", BigDecimal.ONE, "l", "c", null);
        LogisticsReadItem delivered = new LogisticsReadItem("a4", "DELIVERED", "t", "u", BigDecimal.ONE, "l", "c", null);
        LogisticsReadItem depleted = new LogisticsReadItem("a5", "DEPLETED", "t", "u", BigDecimal.ONE, "l", "c", null);

        DonationReadModel model = new DonationReadModel("f1", "USD", "camp-1", 100L, 100L, 0L, 0L, 0L, "ACTIVE",
                List.of(registered, dispatched, received, delivered, depleted));

        PublicDonationTrackingDTO dto = mapper.toPublicDTO(model);

        assertEquals(PublicCustodianCategory.UNCATEGORIZED, dto.logistics().get(0).custodianCategory());
        assertEquals(PublicCustodianCategory.LOGISTICS_PARTNER, dto.logistics().get(1).custodianCategory());
        assertEquals(PublicCustodianCategory.REGIONAL_WAREHOUSE, dto.logistics().get(2).custodianCategory());
        assertEquals(PublicCustodianCategory.LAST_MILE_CARRIER, dto.logistics().get(3).custodianCategory());
        assertEquals(PublicCustodianCategory.UNCATEGORIZED, dto.logistics().get(4).custodianCategory());
    }

    @Test
    void shouldThrowOnInvalidLifecycleStatus() {
        LogisticsReadItem invalid = new LogisticsReadItem("a1", "UNKNOWN", "t", "u", BigDecimal.ONE, "l", "c", null);
        DonationReadModel model = new DonationReadModel("f1", "USD", "camp-1", 100L, 100L, 0L, 0L, 0L, "ACTIVE", List.of(invalid));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> mapper.toPublicDTO(model));
        assertEquals("Unexpected lifecycleStatus: UNKNOWN", ex.getMessage());
    }

    // --- Ficha R-6, G-1: título de la convocatoria y nombre de la organización ---

    @Test
    void withAKnownCampaign_carriesItsTitleAndTheOrganizationName() {
        when(campaignSummaries.findByCampaignRef("camp-1"))
                .thenReturn(Optional.of(new CampaignPublicSummary("Invierno", "Fundación Uno")));

        PublicDonationTrackingDTO dto = mapper.toPublicDTO(activeIn("camp-1"));

        assertEquals("Invierno", dto.campaignTitle());
        assertEquals("Fundación Uno", dto.organizationName());
    }

    @Test
    void anOrganizationWithoutName_givesTheTitleWithoutOrganizationName() {
        when(campaignSummaries.findByCampaignRef("camp-1"))
                .thenReturn(Optional.of(new CampaignPublicSummary("Invierno", null)));

        PublicDonationTrackingDTO dto = mapper.toPublicDTO(activeIn("camp-1"));

        assertEquals("Invierno", dto.campaignTitle());
        assertNull(dto.organizationName());
    }

    @Test
    void anUnknownCampaign_givesNeither() {
        PublicDonationTrackingDTO dto = mapper.toPublicDTO(activeIn("camp-missing"));

        assertNull(dto.campaignTitle());
        assertNull(dto.organizationName());
    }

    /** Rama defensiva de la ficha: sin {@code campaignRef} no se consulta nada y no hay título ni organización. */
    @Test
    void withoutCampaign_givesNeither_andDoesNotAskThePort() {
        when(campaignSummaries.findByCampaignRef(any()))
                .thenReturn(Optional.of(new CampaignPublicSummary("Invierno", "Fundación Uno")));

        PublicDonationTrackingDTO dto = mapper.toPublicDTO(activeIn(null));

        assertNull(dto.campaignTitle());
        assertNull(dto.organizationName());
        verify(campaignSummaries, never()).findByCampaignRef(any());
    }

    /** DD-76: el seguimiento no lleva ni el fondo ni el donante. */
    @Test
    void theTrackingResponse_hasNoFundIdNorDonorRef() {
        assertThat(PublicDonationTrackingDTO.class.getRecordComponents()).extracting(c -> c.getName())
                .doesNotContain("fundId", "donorRef");
    }

    private static DonationReadModel activeIn(String campaignRef) {
        return new DonationReadModel("f1", "USD", campaignRef, 100L, 100L, 0L, 0L, 0L, "ACTIVE", List.of());
    }

    // --- Ficha R-6, G-2: fecha del pago (primera y última liquidación) ---

    @Test
    void withClearings_theSnapshotCarriesFirstAndLastClearedAt_inIso8601Utc() {
        Instant first = Instant.parse("2026-10-03T14:00:00Z");
        Instant last = Instant.parse("2026-10-20T09:30:15.250Z");
        when(clearingDates.findClearingDates("f1")).thenReturn(Optional.of(new ClearingDates(first, last)));

        PublicDonationTrackingDTO dto = mapper.toPublicDTO(activeIn("camp-1"));

        assertEquals("2026-10-03T14:00:00Z", dto.financialSnapshot().firstClearedAt());
        assertEquals("2026-10-20T09:30:15.250Z", dto.financialSnapshot().lastClearedAt());
    }

    @Test
    void withoutClearings_theSnapshotHasNeitherDate() {
        PublicDonationTrackingDTO dto = mapper.toPublicDTO(activeIn("camp-1"));

        assertNull(dto.financialSnapshot().firstClearedAt());
        assertNull(dto.financialSnapshot().lastClearedAt());
    }
}
