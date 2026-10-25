package com.traceability.app.web.tracking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.app.TraceabilityApplication;
import com.traceability.contracts.SequenceRange;
import com.traceability.core.application.security.AssetRefService;
import com.traceability.core.application.security.TrackingCodeService;
import com.traceability.core.infrastructure.persistence.mongo.TraceabilityEventDocument;
import com.traceability.core.infrastructure.projection.mongo.documents.AssetHistoryProjectionDocument;
import com.traceability.core.infrastructure.projection.mongo.documents.AssetIndexDocument;
import com.traceability.core.infrastructure.projection.mongo.documents.DonationProjectionDocument;
import com.traceability.crypto.application.port.out.MerkleBatchRepositoryPort;
import com.traceability.crypto.domain.AnchorStatus;
import com.traceability.crypto.domain.MerkleBatch;
import com.traceability.crypto.domain.MerkleTree;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ficha R-6, Bloque C, por HTTP con Mongo real y proyecciones construidas a mano (plan C, versión 2): el test 8 (ningún
 * {@code parentAssetRef} apunta fuera de {@code logistics}) y la medición de §6 (historial con un lote de 1000 hojas
 * frente a uno de 5). La división real y el lote anclado real del recorrido están en {@code GoldenPathHttpIntegrationTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = TraceabilityApplication.class)
@Testcontainers
@TestPropertySource(properties = {
    "crypto.anchor.poll.delay=9999999",
    "crypto.anchor.submit.delay=9999999",
    "crypto.anchor.stuck-monitor.delay=9999999",
    "traceability.anchor.producer.interval-ms=9999999",
    "crypto.web3j.private-key=0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
    "crypto.web3j.node-url=http://dummy-node",
    "spring.ai.openai.api-key=dummy-api-key"
})
class TrackingLineageAndAnchorHttpIntegrationTest {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:6.0")).withCommand("--replSet", "rs0");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }

    @LocalServerPort private int port;
    @Autowired private TrackingCodeService trackingCodes;
    @Autowired private AssetRefService assetRefs;
    @Autowired private MongoTemplate mongoTemplate;
    @Autowired private MerkleBatchRepositoryPort batches;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    private HttpResponse<String> tracking(String path, String trackingCode) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/donations/tracking" + path))
                .header("Authorization", "Bearer " + trackingCode).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static DonationProjectionDocument.LogisticsProjection lot(String assetId, String parentAssetId) {
        DonationProjectionDocument.LogisticsProjection l = new DonationProjectionDocument.LogisticsProjection();
        l.setAssetId(assetId);
        l.setLifecycleStatus("REGISTERED");
        l.setAssetType("BLANKET");
        l.setUnitOfMeasure("UNITS");
        l.setQuantity(BigDecimal.ONE);
        l.setCurrentLocation("warehouse-1");
        l.setCurrentCustodian("cust-1");
        l.setParentAssetRef(parentAssetId);
        return l;
    }

    private void donationWith(String fundId, List<DonationProjectionDocument.LogisticsProjection> lots) {
        DonationProjectionDocument doc = new DonationProjectionDocument();
        doc.setProjectionId(fundId);
        doc.setCurrency("COP");
        doc.setStatus("ACTIVE");
        doc.setLogistics(new ArrayList<>(lots));
        mongoTemplate.save(doc);
        for (DonationProjectionDocument.LogisticsProjection l : lots) {
            AssetIndexDocument index = new AssetIndexDocument();
            index.setAssetId(l.getAssetId());
            index.setProjectionId(fundId);
            mongoTemplate.save(index);
        }
    }

    // Test 8: una donación cuyo activo es hijo de un activo de OTRA donación no trae parentAssetRef
    @Test
    void t8_aLotWhoseParentBelongsToAnotherDonation_hasNoParentAssetRef() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String otherFund = "fund-other-" + suffix;
        String otherAsset = "asset-other-" + suffix;
        String mineFund = "fund-mine-" + suffix;
        donationWith(otherFund, List.of(lot(otherAsset, null)));
        donationWith(mineFund, List.of(lot("asset-mine-" + suffix, otherAsset)));

        HttpResponse<String> r = tracking("", trackingCodes.generate(mineFund, Instant.now().plusSeconds(3600)));

        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        JsonNode logistics = json.readTree(r.body()).get("logistics");
        assertThat(logistics).hasSize(1);
        assertThat(logistics.get(0).has("parentAssetRef")).isFalse();
        assertThat(r.body()).doesNotContain(assetRefs.computeRef(otherAsset)).doesNotContain(otherAsset)
                .doesNotContain("rootAssetRef");
    }

    // ---- Medición del plan C, §6 ----

    /** Un activo con 5 eventos dentro de un lote ANCHORED de {@code leafCount} hojas, con sus proyecciones. */
    private String assetInAnchoredBatch(int leafCount) {
        String suffix = UUID.randomUUID().toString();
        String fundId = "fund-m-" + suffix;
        String assetId = "asset-m-" + suffix;
        String batchId = "batch-m-" + suffix;
        Map<String, SequenceRange> coverage = new LinkedHashMap<>();
        List<String> leaves = new ArrayList<>();
        List<TraceabilityEventDocument> events = new ArrayList<>();
        // Los demás streams van antes del activo ("a…" < "asset-m-…"), así el activo es la cola del lote
        int others = leafCount - 5;
        for (int i = 0; i < others; i++) {
            String stream = String.format("a-%04d-%s", i, suffix);
            coverage.put(stream, new SequenceRange(1, 1));
            String h = sha256Hex(stream + "#1");
            leaves.add(h);
            events.add(event(stream, 1, h, batchId));
        }
        coverage.put(assetId, new SequenceRange(1, 5));
        List<AssetHistoryProjectionDocument.AssetTransition> transitions = new ArrayList<>();
        for (long s = 1; s <= 5; s++) {
            String h = sha256Hex(assetId + "#" + s);
            leaves.add(h);
            events.add(event(assetId, s, h, batchId));
            transitions.add(AssetHistoryProjectionDocument.AssetTransition.builder().eventType("ASSET_REGISTERED")
                    .status("REGISTERED").timestamp("2026-10-0" + s + "T10:00:00Z").sequence(s).build());
        }
        mongoTemplate.insertAll(events);
        batches.save(new MerkleBatch(batchId, coverage, MerkleTree.build(leaves).getRoot(), leaves,
                Instant.parse("2026-10-20T10:00:00Z"), AnchorStatus.ANCHORED, "ganache-local", "0xcontract", 1L,
                "0x" + "ab".repeat(32), Instant.parse("2026-10-20T11:00:00Z"), Instant.parse("2026-10-20T12:00:00Z"), 7L, null));
        donationWith(fundId, List.of(lot(assetId, null)));
        mongoTemplate.save(AssetHistoryProjectionDocument.builder().assetId(assetId).transitions(transitions).build());
        return fundId + "|" + assetId;
    }

    private static TraceabilityEventDocument event(String streamId, long sequence, String eventHash, String batchId) {
        return TraceabilityEventDocument.builder().eventId(UUID.randomUUID().toString()).streamId(streamId)
                .aggregateType("PhysicalAsset").sequence(sequence).eventType("ASSET_REGISTERED")
                .eventHash(eventHash).previousHash("prev").merkleBatchId(batchId).build();
    }

    private double medianMillis(String fundId, String assetId, int expectedPathLength) throws Exception {
        String code = trackingCodes.generate(fundId, Instant.now().plusSeconds(3600));
        String path = "/assets/" + assetRefs.computeRef(assetId) + "/history";
        HttpResponse<String> first = tracking(path, code);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        JsonNode steps = json.readTree(first.body()).get("history");
        assertThat(steps).hasSize(5);
        for (JsonNode step : steps) {
            assertThat(step.get("anchorStatus").asText()).isEqualTo("ANCHORED");
            JsonNode proof = step.get("anchorProof");
            assertThat(proof.get("path")).hasSize(expectedPathLength);
            String node = step.get("eventHash").asText();
            for (JsonNode s : proof.get("path")) {
                String sibling = s.get("hash").asText();
                node = sha256Hex(s.get("side").asText().equals("LEFT") ? sibling + node : node + sibling);
            }
            assertThat(node).isEqualTo(proof.get("merkleRoot").asText());
        }
        for (int i = 0; i < 5; i++) {
            tracking(path, code);
        }
        List<Double> times = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            long start = System.nanoTime();
            assertThat(tracking(path, code).statusCode()).isEqualTo(200);
            times.add((System.nanoTime() - start) / 1_000_000.0);
        }
        times.sort(Double::compareTo);
        return (times.get(9) + times.get(10)) / 2;
    }

    /**
     * Plan C, §6: mediana de 20 llamadas tras 5 de calentamiento, con un lote de 1000 hojas (el máximo,
     * {@code max-events-per-batch}) y con uno de 5. No afirma un umbral de tiempo: la cifra se anota en
     * {@code estado-fase6.md}; si la diferencia pasa de 50 ms, se pregunta a Manuel. Sí afirma que las pruebas verifican.
     */
    @Test
    void measurement_historyWithA1000LeafBatch_versusA5LeafBatch() throws Exception {
        String[] big = assetInAnchoredBatch(1000).split("\\|");
        String[] small = assetInAnchoredBatch(5).split("\\|");

        double bigMs = medianMillis(big[0], big[1], 10);
        double smallMs = medianMillis(small[0], small[1], 3);

        System.out.printf("[medición plan C §6] historial: mediana con 1000 hojas = %.2f ms; con 5 hojas = %.2f ms; diferencia = %.2f ms%n",
                bigMs, smallMs, bigMs - smallMs);
    }

    private static String sha256Hex(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
