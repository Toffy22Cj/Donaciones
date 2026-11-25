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
        assetHistoryMapper = new AssetHistoryMapper(new com.traceability.core.application.service.PublicZoneResolver(
                locationReferenceService), publicDonationMapper, anchors);
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
        // Enmienda 2 de ADR-021-D, E8 (Q-21D2-1 (a)), que reemplaza a E1: los demás pasos con foto llevan los mismos dos
        // campos; sin foto, evidenceRegistered: false y sin evidenceRef
        com.fasterxml.jackson.databind.JsonNode registered = dto.get("history").get(0);
        assertFalse(registered.get("evidenceRegistered").asBoolean());
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

    // Enmienda 2 de ADR-029, D5 (plan de la zona, tests 8 y 11): un paso nuevo muestra su zona sellada sin consultar
    // location_reference, también el despacho; un paso viejo sigue con el respaldo
    @Test
    void zone_aNewStepShowsItsSealedZone_withoutReadingLocationReference_andAnOldStepFallsBack() {
        when(publicDonationMapper.mapCustodian(Mockito.anyString())).thenReturn(PublicCustodianCategory.UNCATEGORIZED);
        when(locationReferenceService.resolveZone("LOC-VIEJA")).thenReturn(Optional.of("Zona vieja"));
        AssetHistoryPublicDTO dto = assetHistoryMapper.toDto(new AssetHistoryReadModel("asset-z", List.of(
                new AssetTransitionReadModel("ASSET_REGISTERED", "REGISTERED", "c", "LOC-VIEJA",
                        Instant.parse("2026-10-01T10:00:00Z"), 1L),
                new AssetTransitionReadModel("ASSET_DISPATCHED", "DISPATCHED", "c", "Bodega norte",
                        Instant.parse("2026-10-02T10:00:00Z"), 2L, "MEDELLÍN, ANTIOQUIA"),
                new AssetTransitionReadModel("ASSET_RECEIVED", "RECEIVED", "c", "Centro",
                        Instant.parse("2026-10-03T10:00:00Z"), 3L, "ABEJORRAL, ANTIOQUIA"))));

        assertEquals("Zona vieja", dto.history().get(0).locationZone());
        assertEquals("MEDELLÍN, ANTIOQUIA", dto.history().get(1).locationZone());
        assertEquals("ABEJORRAL, ANTIOQUIA", dto.history().get(2).locationZone());
        Mockito.verify(locationReferenceService, Mockito.never()).resolveZone("Bodega norte");
        Mockito.verify(locationReferenceService, Mockito.never()).resolveZone("Centro");
    }

    // ── Enmienda 2 de ADR-021-D, E8 (Q-21D2-1 (a)): la huella de cada paso con foto ──

    private static final String REF_ACTIVO = "ref-opaca-1";
    private static final String H_REG = "sha256:" + "1".repeat(64);
    private static final String H_DIS = "sha256:" + "2".repeat(64);
    private static final String H_REC = "sha256:" + "3".repeat(64);
    private static final String H_ENT = "sha256:" + "4".repeat(64);

    private AssetHistoryReadModel fourSteps(String asset, String reg, String dis, String rec, String ent) {
        when(publicDonationMapper.mapCustodian(Mockito.anyString())).thenReturn(PublicCustodianCategory.UNCATEGORIZED);
        com.traceability.contracts.TrackingAnchorStatus pending = com.traceability.contracts.TrackingAnchorStatus.PENDING;
        when(anchors.anchorBySequence(asset)).thenReturn(Map.of(
                1L, new AssetAnchorStatusPort.StepAnchor(pending, "h1", null, reg),
                2L, new AssetAnchorStatusPort.StepAnchor(pending, "h2", null, dis),
                3L, new AssetAnchorStatusPort.StepAnchor(pending, "h3", null, rec),
                4L, new AssetAnchorStatusPort.StepAnchor(pending, "h4", null, ent)));
        return new AssetHistoryReadModel(asset, List.of(
                new AssetTransitionReadModel("ASSET_REGISTERED", "REGISTERED", "c", "L", Instant.parse("2026-10-01T10:00:00Z"), 1L, "MEDELLÍN, ANTIOQUIA"),
                new AssetTransitionReadModel("ASSET_DISPATCHED", "DISPATCHED", "c", "L", Instant.parse("2026-10-02T10:00:00Z"), 2L, "MEDELLÍN, ANTIOQUIA"),
                new AssetTransitionReadModel("ASSET_RECEIVED", "RECEIVED", "c", "L", Instant.parse("2026-10-03T10:00:00Z"), 3L, "ABEJORRAL, ANTIOQUIA"),
                new AssetTransitionReadModel("ASSET_DELIVERED", "DELIVERED", "c", "L", Instant.parse("2026-10-04T10:00:00Z"), 4L, "BOGOTÁ, D.C.")));
    }

    private com.fasterxml.jackson.databind.JsonNode json(AssetHistoryPublicDTO dto) {
        return new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(dto);
    }

    @Test
    void e8_everyStepWithAPhoto_carriesItsFootprint() {
        com.fasterxml.jackson.databind.JsonNode h = json(assetHistoryMapper.toDto(fourSteps("a-1", H_REG, H_DIS, H_REC, H_ENT)))
                .get("history");
        assertEquals(List.of(H_REG, H_DIS, H_REC, H_ENT), List.of(h.get(0).get("evidenceRef").asText(),
                h.get(1).get("evidenceRef").asText(), h.get(2).get("evidenceRef").asText(), h.get(3).get("evidenceRef").asText()));
        for (int i = 0; i < 4; i++) {
            assertTrue(h.get(i).get("evidenceRegistered").asBoolean(), "paso " + i);
        }
    }

    @Test
    void e8_aStepWithoutAPhoto_isNotRegistered_andHasNoRef() {
        com.fasterxml.jackson.databind.JsonNode h = json(assetHistoryMapper.toDto(fourSteps("a-2", null, H_DIS, null, H_ENT)))
                .get("history");
        for (int i : new int[] {0, 2}) {
            assertFalse(h.get(i).get("evidenceRegistered").asBoolean(), "paso " + i);
            assertFalse(h.get(i).has("evidenceRef"), "paso " + i);
        }
        assertTrue(h.get(1).get("evidenceRegistered").asBoolean());
    }

    @Test
    void e8_aTextThatIsNotAFootprint_neverAppears_inAnyStep() {
        String secret = "nota privada de Juan Pérez";
        AssetHistoryPublicDTO dto = assetHistoryMapper.toDto(fourSteps("a-3", secret, secret, secret, H_ENT));
        com.fasterxml.jackson.databind.JsonNode h = json(dto).get("history");
        for (int i = 0; i < 3; i++) {
            assertFalse(h.get(i).has("evidenceRef"), "paso " + i);
        }
        assertFalse(json(dto).toString().contains("Juan"));
        assertFalse(json(dto).toString().contains("privada"));
    }

    // ── E5: evidencePreviewUrl solo en el paso de la entrega y solo con la copia publicada ──

    @Test
    void e5_aPublishedCopy_putsTheUrlInTheDeliveryStepOnly_andItNeverCarriesAnyCode() {
        com.fasterxml.jackson.databind.JsonNode h = json(assetHistoryMapper.toDto(
                fourSteps("a-4", H_REG, H_DIS, H_REC, H_ENT), REF_ACTIVO, true)).get("history");
        assertEquals("/api/v1/donations/tracking/assets/" + REF_ACTIVO + "/evidence-preview",
                h.get(3).get("evidencePreviewUrl").asText());
        for (int i = 0; i < 3; i++) {
            assertFalse(h.get(i).has("evidencePreviewUrl"), "paso " + i);
        }
        String url = h.get(3).get("evidencePreviewUrl").asText();
        assertFalse(url.contains("?") || url.contains("#") || url.contains("code") || url.contains("Bearer"), url);
        assertEquals(H_ENT, h.get(3).get("evidenceRef").asText()); // la huella sigue siendo la de la ORIGINAL
    }

    @Test
    void e5_withoutAPublishedCopy_noFieldAtAll_andNothingSaysThereIsAnUnpublishedCopy() {
        AssetHistoryPublicDTO dto = assetHistoryMapper.toDto(fourSteps("a-5", H_REG, H_DIS, H_REC, H_ENT), REF_ACTIVO, false);
        String all = json(dto).toString();
        assertFalse(all.contains("evidencePreview"), all);
        assertFalse(all.toLowerCase().contains("preview"), all);
        assertFalse(all.toLowerCase().contains("publish"), all);
        assertFalse(all.toLowerCase().contains("redact"), all);
        // y la sobrecarga sin referencia (la de antes de la Tarea 4) tampoco la lleva
        assertFalse(json(assetHistoryMapper.toDto(fourSteps("a-5", H_REG, H_DIS, H_REC, H_ENT))).toString().contains("evidencePreview"));
    }

    @Test
    void e5_aPublishedCopyWithoutAReferenceToBuildTheUrl_neverBuildsABrokenUrl() {
        assertFalse(json(assetHistoryMapper.toDto(fourSteps("a-6", H_REG, H_DIS, H_REC, H_ENT), null, true))
                .toString().contains("evidencePreview"));
        assertFalse(json(assetHistoryMapper.toDto(fourSteps("a-6", H_REG, H_DIS, H_REC, H_ENT), "  ", true))
                .toString().contains("evidencePreview"));
    }

    @Test
    void e5_theDeliveryStepWithoutEvidence_neverGetsAUrl_evenIfThePortSaidPublished() {
        com.fasterxml.jackson.databind.JsonNode h = json(assetHistoryMapper.toDto(
                fourSteps("a-7", H_REG, H_DIS, H_REC, null), REF_ACTIVO, true)).get("history");
        assertFalse(h.get(3).has("evidencePreviewUrl"));
    }
}
