package com.traceability.app.application.integrity;

import com.traceability.app.config.TraceabilityInfrastructureConfig;
import com.traceability.contracts.AssetAnchorStatusPort.AnchorProof;
import com.traceability.contracts.AssetAnchorStatusPort.ProofStep;
import com.traceability.contracts.AssetAnchorStatusPort.Side;
import com.traceability.contracts.AssetAnchorStatusPort.StepAnchor;
import com.traceability.contracts.SequenceRange;
import com.traceability.contracts.TrackingAnchorStatus;
import com.traceability.core.application.port.out.StreamEventAnchorDataPort;
import com.traceability.core.infrastructure.persistence.mongo.TraceabilityEventDocument;
import com.traceability.crypto.application.port.out.MerkleBatchRepositoryPort;
import com.traceability.crypto.domain.AnchorStatus;
import com.traceability.crypto.domain.MerkleBatch;
import com.traceability.crypto.domain.MerkleTree;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

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
 * Implementación del puerto de G-3 con Mongo real (ficha R-6, §5, G-3 y Enmienda 1, E1; plan del Bloque C, versión 2,
 * §2.4; tests 9a, 9b y C-E5 a C-E9). Lotes y eventos construidos a mano en {@code merkle_batches} y {@code event_store}.
 * Cada caso lleva las aserciones negativas de las otras ramas (E1.5).
 */
@SpringBootTest(classes = AssetAnchorStatusProducerIntegrationTest.TestConfig.class, properties = {"spring.ai.openai.api-key=dummy"})
@Testcontainers
class AssetAnchorStatusProducerIntegrationTest {

    @Configuration
    @SpringBootApplication(scanBasePackages = {
            "com.traceability.core.infrastructure.persistence.mongo",
            "com.traceability.crypto.infrastructure.persistence.mongo"
    })
    @EnableMongoRepositories(basePackages = {
            "com.traceability.core.infrastructure.persistence.mongo",
            "com.traceability.crypto.infrastructure.persistence.mongo"
    })
    @Import({TraceabilityInfrastructureConfig.class, com.traceability.crypto.application.JcsHashAdapter.class,
            com.traceability.core.application.event.EventCanonicalMapper.class, AssetAnchorStatusProducer.class})
    static class TestConfig {
    }

    @Container
    static MongoDBContainer mongoDBContainer = new MongoDBContainer(org.testcontainers.utility.DockerImageName.parse("mongo:6.0"))
            .withCommand("--replSet", "rs0");

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongoDBContainer::getReplicaSetUrl);
    }

    private static final String TARGET = "asset-target";
    private static final Instant ANCHORED_AT = Instant.parse("2026-10-20T12:00:00Z");

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private MerkleBatchRepositoryPort batches;

    @Autowired
    private StreamEventAnchorDataPort streamEvents;

    @Autowired
    private AssetAnchorStatusProducer producer;

    @BeforeEach
    void cleanUp() {
        mongoTemplate.dropCollection("event_store");
        mongoTemplate.dropCollection("merkle_batches");
        mongoTemplate.createCollection("event_store");
        mongoTemplate.createCollection("merkle_batches");
    }

    // ---- 9a, 9b: la tabla de la ficha (§5, C-4) con un lote por cada uno de los diez AnchorStatus ----

    @Test
    void t9a_oneStepPerEachOfTheTenAnchorStatuses_givesTheTableValue() {
        Map<AnchorStatus, TrackingAnchorStatus> table = new LinkedHashMap<>();
        table.put(AnchorStatus.ANCHORED, TrackingAnchorStatus.ANCHORED);
        table.put(AnchorStatus.COLLECTING, TrackingAnchorStatus.IN_BATCH);
        table.put(AnchorStatus.PENDING, TrackingAnchorStatus.IN_BATCH);
        table.put(AnchorStatus.SUBMITTING, TrackingAnchorStatus.IN_BATCH);
        table.put(AnchorStatus.SUBMITTED, TrackingAnchorStatus.IN_BATCH);
        table.put(AnchorStatus.STUCK, TrackingAnchorStatus.IN_BATCH);
        table.put(AnchorStatus.RELEASED, TrackingAnchorStatus.PENDING);
        table.put(AnchorStatus.FAILED, TrackingAnchorStatus.UNDER_REVIEW);
        table.put(AnchorStatus.COLLECTING_FAILED, TrackingAnchorStatus.UNDER_REVIEW);
        table.put(AnchorStatus.ANCHOR_MISMATCH, TrackingAnchorStatus.UNDER_REVIEW);
        assertThat(table.keySet()).containsExactlyInAnyOrder(AnchorStatus.values());

        long seq = 1;
        Map<Long, AnchorStatus> statusBySequence = new LinkedHashMap<>();
        for (AnchorStatus status : table.keySet()) {
            String hash = hashOf(TARGET, seq);
            insertEvent(TARGET, seq, hash, "batch-" + status);
            saveBatch("batch-" + status, status, Map.of(TARGET, new SequenceRange(seq, seq)), List.of(hash));
            statusBySequence.put(seq, status);
            seq++;
        }

        Map<Long, StepAnchor> result = producer.anchorBySequence(TARGET);

        assertThat(result).hasSize(10);
        statusBySequence.forEach((s, status) -> {
            StepAnchor step = result.get(s);
            assertThat(step.status()).as("lote %s", status).isEqualTo(table.get(status));
            assertThat(step.eventHash()).isEqualTo(hashOf(TARGET, s));
            // Solo el ANCHORED con hojas lleva prueba
            if (status == AnchorStatus.ANCHORED) {
                assertThat(step.proof()).isNotNull();
            } else {
                assertThat(step.proof()).as("lote %s", status).isNull();
            }
        });
    }

    @Test
    void t9b_stepWithoutBatch_andStepInAReleasedBatch_arePending() {
        insertEvent(TARGET, 1, hashOf(TARGET, 1), null);
        insertEvent(TARGET, 2, hashOf(TARGET, 2), "batch-released");
        saveBatch("batch-released", AnchorStatus.RELEASED, Map.of(TARGET, new SequenceRange(2, 2)), List.of(hashOf(TARGET, 2)));

        Map<Long, StepAnchor> result = producer.anchorBySequence(TARGET);

        assertThat(result.get(1L)).isEqualTo(new StepAnchor(TrackingAnchorStatus.PENDING, hashOf(TARGET, 1), null));
        assertThat(result.get(2L)).isEqualTo(new StepAnchor(TrackingAnchorStatus.PENDING, hashOf(TARGET, 2), null));
    }

    /** Hallazgo H-C-2: un evento reclamado cuyo lote aún no se guardó (BlockchainAnchorProducer reclama y después guarda). */
    @Test
    void stepWhoseBatchDoesNotExist_isPendingWithoutProof() {
        insertEvent(TARGET, 1, hashOf(TARGET, 1), "batch-not-saved-yet");

        assertThat(producer.anchorBySequence(TARGET).get(1L))
                .isEqualTo(new StepAnchor(TrackingAnchorStatus.PENDING, hashOf(TARGET, 1), null));
    }

    // ---- C-E5 a C-E9: eventHash y anchorProof (Enmienda 1, E1.2) ----

    /** Lote ANCHORED con tres streams; el activo es el segundo de coverage, no el primero. */
    private Fixture anchoredBatchWithThreeStreams() {
        Map<String, SequenceRange> coverage = new LinkedHashMap<>();
        // Insertado en coverage en un orden distinto del de streamId: el índice usa el orden por clave, no el del mapa
        coverage.put("asset-zeta", new SequenceRange(5, 6));
        coverage.put(TARGET, new SequenceRange(2, 4));
        coverage.put("asset-alpha", new SequenceRange(1, 3));
        List<String> leaves = new ArrayList<>();
        for (String stream : List.of("asset-alpha", TARGET, "asset-zeta")) {
            SequenceRange r = coverage.get(stream);
            for (long s = r.fromSequence(); s <= r.toSequence(); s++) {
                String h = hashOf(stream, s);
                insertEvent(stream, s, h, "batch-anchored");
                leaves.add(h);
            }
        }
        // El evento 1 del activo, en un lote anterior pendiente: no es de este lote
        insertEvent(TARGET, 1, hashOf(TARGET, 1), "batch-pending");
        saveBatch("batch-pending", AnchorStatus.PENDING, Map.of(TARGET, new SequenceRange(1, 1)), List.of(hashOf(TARGET, 1)));
        String root = MerkleTree.build(leaves).getRoot();
        saveBatch("batch-anchored", AnchorStatus.ANCHORED, coverage, leaves, root);
        return new Fixture(leaves, root);
    }

    private record Fixture(List<String> leaves, String root) {}

    @Test
    void cE5_anchoredBatch_eventHashFromTheEventStore_leafIndexByCoverage_proofRebuildsTheRoot() {
        Fixture f = anchoredBatchWithThreeStreams();

        Map<Long, StepAnchor> result = producer.anchorBySequence(TARGET);

        assertThat(result.keySet()).containsExactly(1L, 2L, 3L, 4L);
        for (long s = 2; s <= 4; s++) {
            StepAnchor step = result.get(s);
            assertThat(step.status()).isEqualTo(TrackingAnchorStatus.ANCHORED);
            assertThat(step.eventHash()).isEqualTo(eventHashInStore(TARGET, s));
            AnchorProof proof = step.proof();
            assertThat(proof).isNotNull();
            // asset-alpha (3 hojas) va antes: el índice es 3 + (s − 2)
            assertThat(proof.leafIndex()).isEqualTo(3 + (int) (s - 2));
            assertThat(f.leaves().get(proof.leafIndex())).isEqualTo(step.eventHash());
            assertThat(proof.merkleRoot()).isEqualTo(f.root());
            assertThat(proof.transactionHash()).isEqualTo("0xtx-batch-anchored");
            assertThat(proof.network()).isEqualTo("sepolia");
            assertThat(proof.anchoredAt()).isEqualTo(ANCHORED_AT);
            assertThat(verify(step.eventHash(), proof.path(), proof.merkleRoot())).isTrue();
        }
        // Negativa: el paso del lote PENDING no lleva prueba
        assertThat(result.get(1L)).isEqualTo(new StepAnchor(TrackingAnchorStatus.IN_BATCH, hashOf(TARGET, 1), null));
    }

    @Test
    void cE6_pendingBatch_isInBatch_withEventHash_withoutProof() {
        insertEvent(TARGET, 1, hashOf(TARGET, 1), "batch-p");
        saveBatch("batch-p", AnchorStatus.PENDING, Map.of(TARGET, new SequenceRange(1, 1)), List.of(hashOf(TARGET, 1)));

        StepAnchor step = producer.anchorBySequence(TARGET).get(1L);

        assertThat(step.status()).isEqualTo(TrackingAnchorStatus.IN_BATCH);
        assertThat(step.eventHash()).isEqualTo(hashOf(TARGET, 1));
        assertThat(step.proof()).isNull();
    }

    @Test
    void cE7_anchoredLegacyBatchWithoutLeafHashes_isAnchored_withEventHash_withoutProof() {
        insertEvent(TARGET, 1, hashOf(TARGET, 1), "batch-legacy");
        insertEvent("asset-other", 1, hashOf("asset-other", 1), "batch-legacy");
        Map<String, SequenceRange> coverage = Map.of(TARGET, new SequenceRange(1, 1), "asset-other", new SequenceRange(1, 1));
        String root = MerkleTree.build(List.of(hashOf("asset-other", 1), hashOf(TARGET, 1))).getRoot();
        saveBatch("batch-legacy", AnchorStatus.ANCHORED, coverage, null, root);

        StepAnchor step = producer.anchorBySequence(TARGET).get(1L);

        assertThat(step.status()).isEqualTo(TrackingAnchorStatus.ANCHORED);
        assertThat(step.eventHash()).isEqualTo(hashOf(TARGET, 1));
        // No se reconstruye con las hojas actuales, aunque coincidirían
        assertThat(step.proof()).isNull();
    }

    @Test
    void cE8_alteredEvent_isUnderReview_withoutProof_andTheOtherStepsKeepTheirProof() {
        anchoredBatchWithThreeStreams();
        String altered = hashOf("tampered", 3);
        alterEventHash(TARGET, 3, altered);

        Map<Long, StepAnchor> result = producer.anchorBySequence(TARGET);

        assertThat(result.get(3L)).isEqualTo(new StepAnchor(TrackingAnchorStatus.UNDER_REVIEW, altered, null));
        assertThat(result.get(2L).status()).isEqualTo(TrackingAnchorStatus.ANCHORED);
        assertThat(result.get(2L).proof()).isNotNull();
        assertThat(result.get(4L).status()).isEqualTo(TrackingAnchorStatus.ANCHORED);
        assertThat(result.get(4L).proof()).isNotNull();
    }

    /**
     * Distingue calcular el índice de buscar el hash: el evento toma el hash de OTRA hoja del mismo lote. Si se buscara
     * el hash en leafHashes, se encontraría esa otra hoja y saldría una prueba válida para un hecho que no es el mostrado.
     */
    @Test
    void cE9_eventAlteredWithTheHashOfAnotherLeafOfTheSameBatch_isUnderReview_withoutProof() {
        Fixture f = anchoredBatchWithThreeStreams();
        String otherLeaf = f.leaves().get(0); // asset-alpha, 1
        alterEventHash(TARGET, 3, otherLeaf);

        StepAnchor step = producer.anchorBySequence(TARGET).get(3L);

        assertThat(step).isEqualTo(new StepAnchor(TrackingAnchorStatus.UNDER_REVIEW, otherLeaf, null));
    }

    // ---- Incoherencias entre el evento y el lote (§2.4, paso 4.1; R-C-6): UNDER_REVIEW, nunca una prueba ----

    @Test
    void anchoredBatchWhoseCoverageDoesNotIncludeTheStream_isUnderReview_withoutProof() {
        insertEvent(TARGET, 1, hashOf(TARGET, 1), "batch-a");
        saveBatch("batch-a", AnchorStatus.ANCHORED, Map.of("asset-other", new SequenceRange(1, 1)), List.of(hashOf(TARGET, 1)));

        assertThat(producer.anchorBySequence(TARGET).get(1L))
                .isEqualTo(new StepAnchor(TrackingAnchorStatus.UNDER_REVIEW, hashOf(TARGET, 1), null));
    }

    @Test
    void anchoredBatchWhoseRangeDoesNotIncludeTheSequence_isUnderReview_withoutProof() {
        insertEvent(TARGET, 5, hashOf(TARGET, 5), "batch-a");
        saveBatch("batch-a", AnchorStatus.ANCHORED, Map.of(TARGET, new SequenceRange(1, 2)),
                List.of(hashOf(TARGET, 5), hashOf(TARGET, 2)));

        assertThat(producer.anchorBySequence(TARGET).get(5L))
                .isEqualTo(new StepAnchor(TrackingAnchorStatus.UNDER_REVIEW, hashOf(TARGET, 5), null));
    }

    @Test
    void anchoredBatchWithFewerLeavesThanTheIndex_isUnderReview_withoutProof() {
        insertEvent(TARGET, 2, hashOf(TARGET, 2), "batch-a");
        saveBatch("batch-a", AnchorStatus.ANCHORED, Map.of(TARGET, new SequenceRange(1, 2)), List.of(hashOf(TARGET, 1)));

        assertThat(producer.anchorBySequence(TARGET).get(2L))
                .isEqualTo(new StepAnchor(TrackingAnchorStatus.UNDER_REVIEW, hashOf(TARGET, 2), null));
    }

    // ---- Utilidades ----

    /** Verificador de E1.3, escrito sin usar MerkleTree. */
    static boolean verify(String leaf, List<ProofStep> path, String root) {
        String node = leaf;
        for (ProofStep step : path) {
            node = step.side() == Side.LEFT ? sha256Hex(step.hash() + node) : sha256Hex(node + step.hash());
        }
        return node.equals(root);
    }

    static String sha256Hex(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hashOf(String stream, long sequence) {
        return sha256Hex(stream + "#" + sequence);
    }

    private void insertEvent(String streamId, long sequence, String eventHash, String batchId) {
        mongoTemplate.insert(TraceabilityEventDocument.builder()
                .eventId(UUID.randomUUID().toString())
                .streamId(streamId)
                .aggregateType("PhysicalAsset")
                .sequence(sequence)
                .eventType("ASSET_REGISTERED")
                .eventHash(eventHash)
                .previousHash("prev")
                .merkleBatchId(batchId)
                .build());
    }

    private void alterEventHash(String streamId, long sequence, String newHash) {
        mongoTemplate.updateFirst(new Query(Criteria.where("streamId").is(streamId).and("sequence").is(sequence)),
                new Update().set("eventHash", newHash), TraceabilityEventDocument.class);
    }

    private String eventHashInStore(String streamId, long sequence) {
        return mongoTemplate.findOne(new Query(Criteria.where("streamId").is(streamId).and("sequence").is(sequence)),
                TraceabilityEventDocument.class).getEventHash();
    }

    private void saveBatch(String batchId, AnchorStatus status, Map<String, SequenceRange> coverage, List<String> leaves) {
        saveBatch(batchId, status, coverage, leaves, leaves == null ? "root" : MerkleTree.build(leaves).getRoot());
    }

    private void saveBatch(String batchId, AnchorStatus status, Map<String, SequenceRange> coverage, List<String> leaves,
                           String root) {
        batches.save(new MerkleBatch(batchId, coverage, root, leaves, Instant.parse("2026-10-20T10:00:00Z"), status,
                "sepolia", "0xcontract", 1L, "0xtx-" + batchId, Instant.parse("2026-10-20T11:00:00Z"),
                status == AnchorStatus.ANCHORED ? ANCHORED_AT : null, 100L, null));
    }
}
