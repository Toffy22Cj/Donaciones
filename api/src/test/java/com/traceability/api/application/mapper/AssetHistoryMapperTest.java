package com.traceability.api.application.mapper;

import com.traceability.api.application.dto.AssetHistoryPublicDTO;
import com.traceability.api.application.dto.PublicCustodianCategory;
import com.traceability.api.application.dto.PublicTransitionDTO;
import com.traceability.contracts.AssetAnchorStatusPort;
import com.traceability.contracts.AssetAnchorStatusPort.AnchorProof;
import com.traceability.contracts.AssetAnchorStatusPort.ProofStep;
import com.traceability.contracts.AssetAnchorStatusPort.Side;
import com.traceability.contracts.AssetAnchorStatusPort.StepAnchor;
import com.traceability.contracts.TrackingAnchorStatus;
import com.traceability.core.application.service.LocationReferenceService;
import com.traceability.core.application.port.out.AssetHistoryReadModel;
import com.traceability.core.application.port.out.AssetTransitionReadModel;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class AssetHistoryMapperTest {

    private LocationReferenceService locationReferenceService;
    private PublicDonationMapper publicDonationMapper;
    private AssetAnchorStatusPort anchors;
    private AssetHistoryMapper assetHistoryMapper;

    @BeforeEach
    void setUp() {
        locationReferenceService = Mockito.mock(LocationReferenceService.class);
        publicDonationMapper = Mockito.mock(PublicDonationMapper.class);
        anchors = Mockito.mock(AssetAnchorStatusPort.class);
        when(anchors.anchorBySequence(Mockito.anyString())).thenReturn(Map.of());
        assetHistoryMapper = new AssetHistoryMapper(locationReferenceService, publicDonationMapper, anchors);
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
                Instant.parse("2026-09-06T10:00:00Z"),
                1L
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
                    new AssetTransitionReadModel("ASSET_" + status, status, null, null, Instant.parse("2026-10-07T10:00:00Z"), 1L))));

            assertEquals(PublicCustodianCategory.UNCATEGORIZED, dto.history().get(0).custodianCategory());
        }
        Mockito.verify(publicDonationMapper, Mockito.never()).mapCustodian("SPLIT");
    }

    @Test
    void toDto_withNullDocument_shouldReturnEmptyHistory() {
        AssetHistoryPublicDTO dto = assetHistoryMapper.toDto(null);
        assertTrue(dto.history().isEmpty());
    }

    // ---- Ficha R-6, G-3 y Enmienda 1, E1 (plan del Bloque C, versión 2, §2.5; tests C-E10 y 9d) ----

    private static final String H1 = "a".repeat(64);
    private static final String H2 = "b".repeat(64);

    private static AssetTransitionReadModel step(long sequence) {
        return new AssetTransitionReadModel("ASSET_REGISTERED", "REGISTERED", "cust", "LOC", Instant.parse("2026-10-01T10:00:00Z"), sequence);
    }

    private static AnchorProof proof() {
        return new AnchorProof("c".repeat(64), 3, List.of(new ProofStep(Side.RIGHT, "d".repeat(64)),
                new ProofStep(Side.LEFT, "e".repeat(64))), "0xtx", "sepolia", Instant.parse("2026-10-20T12:00:00Z"));
    }

    // Cruce por sequence, no por posición: el mapa trae los pasos en otro orden que el historial
    @Test
    void g3_eachStepTakesItsAnchorBySequence_notByPosition() {
        Map<Long, StepAnchor> bySequence = new LinkedHashMap<>();
        bySequence.put(5L, new StepAnchor(TrackingAnchorStatus.ANCHORED, H2, proof()));
        bySequence.put(2L, new StepAnchor(TrackingAnchorStatus.IN_BATCH, H1, null));
        when(anchors.anchorBySequence("asset-9")).thenReturn(bySequence);

        AssetHistoryPublicDTO dto = assetHistoryMapper.toDto(new AssetHistoryReadModel("asset-9", List.of(step(2), step(5))));

        PublicTransitionDTO first = dto.history().get(0);
        PublicTransitionDTO second = dto.history().get(1);
        assertEquals("IN_BATCH", first.anchorStatus());
        assertEquals(H1, first.eventHash());
        assertNull(first.anchorProof());
        assertEquals("ANCHORED", second.anchorStatus());
        assertEquals(H2, second.eventHash());
        assertEquals("c".repeat(64), second.anchorProof().merkleRoot());
        assertEquals(3, second.anchorProof().leafIndex());
        assertEquals(List.of(new ProofStep(Side.RIGHT, "d".repeat(64)), new ProofStep(Side.LEFT, "e".repeat(64))),
                second.anchorProof().path());
        assertEquals("0xtx", second.anchorProof().transactionHash());
        assertEquals("sepolia", second.anchorProof().network());
        assertEquals("2026-10-20T12:00:00Z", second.anchorProof().anchoredAt());
        // Una consulta al puerto por historial, con el assetId del activo
        Mockito.verify(anchors, Mockito.times(1)).anchorBySequence("asset-9");
    }

    // C-E10: paso sin evento en el mapa → PENDING, sin eventHash ni anchorProof (P-R6C-3, P-C-4)
    @Test
    void cE10_stepWithoutEventInTheMap_isPending_withoutEventHashNorProof() throws Exception {
        when(anchors.anchorBySequence("asset-9")).thenReturn(Map.of(1L, new StepAnchor(TrackingAnchorStatus.ANCHORED, H1, proof())));

        AssetHistoryPublicDTO dto = assetHistoryMapper.toDto(new AssetHistoryReadModel("asset-9", List.of(step(0))));

        PublicTransitionDTO t = dto.history().get(0);
        assertEquals("PENDING", t.anchorStatus());
        assertNull(t.eventHash());
        assertNull(t.anchorProof());
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(t);
        assertTrue(json.contains("\"anchorStatus\":\"PENDING\""), json);
        assertFalse(json.contains("eventHash"), json);
        assertFalse(json.contains("anchorProof"), json);
    }

    // 9d (ampliado): la respuesta no contiene sequence, streamId, merkleBatchId, previousHash, leafHashes ni leafCount
    @Test
    void t9d_theJsonHasNoSequenceStreamIdBatchIdPreviousHashLeafHashesNorLeafCount() throws Exception {
        when(anchors.anchorBySequence("asset-9")).thenReturn(Map.of(7L, new StepAnchor(TrackingAnchorStatus.ANCHORED, H1, proof())));

        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                assetHistoryMapper.toDto(new AssetHistoryReadModel("asset-9", List.of(step(7)))));

        assertTrue(json.contains("\"anchorProof\""), json);
        for (String forbidden : List.of("sequence", "streamId", "merkleBatchId", "previousHash", "leafHashes", "leafCount", "asset-9")) {
            assertFalse(json.contains(forbidden), forbidden + " en " + json);
        }
    }

    // ── ADR-051, Tarea 5; enmienda de ADR-021-D (E1 a E3, tests 1 a 5): la huella en el paso de la entrega ──

    private static final String HUELLA = "sha256:" + "0123456789abcdef".repeat(4);

    private com.fasterxml.jackson.databind.JsonNode deliveredStepWith(String evidenceRef) throws Exception {
        // H-T5-1, opción (a): la huella llega en el StepAnchor del puerto de contracts, sin otra consulta
        when(anchors.anchorBySequence("asset-ev")).thenReturn(Map.of(
                1L, new AssetAnchorStatusPort.StepAnchor(com.traceability.contracts.TrackingAnchorStatus.PENDING, "h1", null, null),
                2L, new AssetAnchorStatusPort.StepAnchor(com.traceability.contracts.TrackingAnchorStatus.PENDING, "h2", null, evidenceRef)));
        AssetHistoryReadModel history = new AssetHistoryReadModel("asset-ev", List.of(
                new AssetTransitionReadModel("ASSET_REGISTERED", "REGISTERED", "cust-1", "LOC-1", Instant.parse("2026-09-06T10:00:00Z"), 1L),
                new AssetTransitionReadModel("ASSET_DELIVERED", "DELIVERED", "cust-f", "LOC-2", Instant.parse("2026-09-07T10:00:00Z"), 2L)));
        return new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(assetHistoryMapper.toDto(history));
    }

    @Test
    void t5_aDeliveryWithAValidFootprint_carriesEvidenceRegisteredAndTheRef() throws Exception {
        com.fasterxml.jackson.databind.JsonNode dto = deliveredStepWith(HUELLA);

        com.fasterxml.jackson.databind.JsonNode delivered = dto.get("history").get(1);
        assertTrue(delivered.get("evidenceRegistered").asBoolean());
        assertEquals(HUELLA, delivered.get("evidenceRef").asText());
        // E1: los demás pasos no llevan ninguno de los dos campos
        com.fasterxml.jackson.databind.JsonNode registered = dto.get("history").get(0);
        assertFalse(registered.has("evidenceRegistered"));
        assertFalse(registered.has("evidenceRef"));
    }

    @Test
    void t5_aLegacyFreeTextEvidence_isRegisteredWithoutTheRef_andTheTextAppearsNowhere() throws Exception {
        com.fasterxml.jackson.databind.JsonNode dto = deliveredStepWith("acta-1 Juan Pérez");

        com.fasterxml.jackson.databind.JsonNode delivered = dto.get("history").get(1);
        assertTrue(delivered.get("evidenceRegistered").asBoolean());
        assertFalse(delivered.has("evidenceRef"));
        assertFalse(dto.toString().contains("acta-1"));
        assertFalse(dto.toString().contains("Juan"));
    }

    @Test
    void t5_aNullEvidence_isNotRegistered() throws Exception {
        com.fasterxml.jackson.databind.JsonNode delivered = deliveredStepWith(null).get("history").get(1);

        assertFalse(delivered.get("evidenceRegistered").asBoolean());
        assertFalse(delivered.has("evidenceRef"));
    }

    @Test
    void t5_almostValidFootprints_neverCarryTheRef() throws Exception {
        for (String almost : List.of("SHA256:" + HUELLA.substring(7), HUELLA.substring(0, HUELLA.length() - 1),
                HUELLA + "0", "sha256:" + HUELLA.substring(7).toUpperCase(), " " + HUELLA, HUELLA + " ")) {
            com.fasterxml.jackson.databind.JsonNode delivered = deliveredStepWith(almost).get("history").get(1);
            assertTrue(delivered.get("evidenceRegistered").asBoolean(), almost);
            assertFalse(delivered.has("evidenceRef"), almost);
        }
    }
}
