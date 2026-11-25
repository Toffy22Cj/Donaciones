package com.traceability.core.infrastructure.persistence.mongo;

import com.traceability.contracts.HashPort;
import com.traceability.core.application.port.out.StreamEventAnchorDataPort.StreamEventAnchorData;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.index.MongoPersistentEntityIndexResolver;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Puerto nuevo de solo lectura de {@code core} (ficha R-6, Enmienda 1, E1; plan del Bloque C, versión 2, §2.3 (2) y
 * P-C-2): hash y lote de cada evento de un stream, en orden de {@code sequence}, con una consulta por
 * {@code idx_stream_sequence} (§6). {@code EventBatchMembershipPort} no cambia.
 */
@SpringBootTest(classes = MongoStreamEventAnchorDataAdapterTest.TestConfig.class)
@Testcontainers
class MongoStreamEventAnchorDataAdapterTest {

    @Container
    static MongoDBContainer mongoDBContainer = new MongoDBContainer(DockerImageName.parse("mongo:6.0"))
            .withCommand("--replSet", "rs0");

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongoDBContainer::getReplicaSetUrl);
    }

    @Configuration
    @SpringBootApplication(scanBasePackages = "com.traceability.core.infrastructure.persistence.mongo")
    @org.springframework.data.mongodb.repository.config.EnableMongoRepositories(basePackages = "com.traceability.core.infrastructure.persistence.mongo")
    static class TestConfig {
    }

    @Autowired
    private MongoTemplate mongoTemplate;

    @MockBean
    private HashPort hashPort;

    @MockBean
    private com.traceability.core.application.event.EventCanonicalMapper eventCanonicalMapper;

    @Autowired
    private MongoStreamEventAnchorDataAdapter adapter;

    @BeforeEach
    void setUp() {
        mongoTemplate.dropCollection(TraceabilityEventDocument.class);
        // Los índices de producción, desde las anotaciones de TraceabilityEventDocument (idx_stream_sequence)
        IndexOperations indexOps = mongoTemplate.indexOps(TraceabilityEventDocument.class);
        new MongoPersistentEntityIndexResolver(mongoTemplate.getConverter().getMappingContext())
                .resolveIndexFor(TraceabilityEventDocument.class).forEach(indexOps::ensureIndex);
    }

    @AfterEach
    void tearDown() {
        mongoTemplate.getDb().runCommand(new Document("profile", 0));
        mongoTemplate.dropCollection(TraceabilityEventDocument.class);
    }

    @Test
    void findByStream_returnsSequenceHashAndBatchOfOnlyThatStream_inSequenceOrder() {
        // Insertadas desordenadas, y con otro stream que no debe aparecer
        mongoTemplate.insert(event("asset-B", 2, hash('b', 2), "batch-1"));
        mongoTemplate.insert(event("asset-A", 3, hash('a', 3), null));
        mongoTemplate.insert(event("asset-A", 1, hash('a', 1), "batch-1"));
        mongoTemplate.insert(event("asset-A", 2, hash('a', 2), "batch-2"));
        mongoTemplate.insert(event("asset-AA", 1, hash('c', 1), "batch-1"));

        List<StreamEventAnchorData> data = adapter.findByStream("asset-A");

        assertThat(data).containsExactly(
                new StreamEventAnchorData(1, hash('a', 1), "batch-1"),
                new StreamEventAnchorData(2, hash('a', 2), "batch-2"),
                new StreamEventAnchorData(3, hash('a', 3), null));
    }

    // ADR-051, Tarea 5 (P-T5-1) ampliada por la Tarea 4 (ADR-021-D Enmienda 2, E8, Q-21D2-1 (a)): el evidenceRef del payload
    // de los cuatro eventos que pueden llevar foto (registrar, despachar, recibir y entregar), tal cual (huella o texto
    // libre heredado); de cualquier otro evento, nulo. Quién lo expone y cómo lo decide api
    @Test
    void t5_findByStream_carriesTheEvidenceRef_ofThePhotoEventsOnly_asStored() {
        String huella = "sha256:" + "a".repeat(64);
        mongoTemplate.insert(event("asset-A", 1, hash('a', 1), null));
        TraceabilityEventDocument withEvidenceLikeField = event("asset-A", 2, hash('a', 2), null);
        withEvidenceLikeField.setEventType("ASSET_SPLIT"); // un evento sin foto posible, aunque su payload traiga el campo
        withEvidenceLikeField.setPayload(new java.util.HashMap<>(java.util.Map.of("evidenceRef", "no-es-entrega")));
        mongoTemplate.insert(withEvidenceLikeField);
        mongoTemplate.insert(delivered("asset-A", 3, hash('a', 3), huella));
        mongoTemplate.insert(delivered("asset-B", 1, hash('b', 1), "acta-1"));
        mongoTemplate.insert(delivered("asset-C", 1, hash('c', 1), null));

        assertThat(adapter.findByStream("asset-A")).containsExactly(
                new StreamEventAnchorData(1, hash('a', 1), null, null),
                new StreamEventAnchorData(2, hash('a', 2), null, null),
                new StreamEventAnchorData(3, hash('a', 3), null, huella));
        assertThat(adapter.findByStream("asset-B")).extracting(StreamEventAnchorData::evidenceRef).containsExactly("acta-1");
        assertThat(adapter.findByStream("asset-C")).extracting(StreamEventAnchorData::evidenceRef).containsOnlyNulls();
    }

    @Test
    void e8_findByStream_carriesTheFootprint_ofRegisterDispatchAndReceive_butNotOfAnyOtherEvent() {
        String r = "sha256:" + "1".repeat(64);
        String d = "sha256:" + "2".repeat(64);
        String c = "sha256:" + "3".repeat(64);
        mongoTemplate.insert(withType("asset-E", 1, "ASSET_REGISTERED", r));
        mongoTemplate.insert(withType("asset-E", 2, "ASSET_DISPATCHED", d));
        mongoTemplate.insert(withType("asset-E", 3, "ASSET_RECEIVED", c));
        mongoTemplate.insert(withType("asset-E", 4, "ASSET_SPLIT", "sha256:" + "9".repeat(64)));
        mongoTemplate.insert(withType("asset-E", 5, "ASSET_REGISTERED", null));

        assertThat(adapter.findByStream("asset-E")).extracting(StreamEventAnchorData::evidenceRef)
                .containsExactly(r, d, c, null, null);
    }

    private static TraceabilityEventDocument withType(String streamId, long sequence, String type, String evidenceRef) {
        TraceabilityEventDocument e = event(streamId, sequence, hash('e', (int) sequence), null);
        e.setEventType(type);
        java.util.Map<String, Object> payload = new java.util.HashMap<>();
        if (evidenceRef != null) {
            payload.put("evidenceRef", evidenceRef);
        }
        e.setPayload(payload);
        return e;
    }

    private static TraceabilityEventDocument delivered(String streamId, long sequence, String eventHash, String evidenceRef) {
        TraceabilityEventDocument d = event(streamId, sequence, eventHash, null);
        d.setEventType("ASSET_DELIVERED");
        java.util.Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("evidenceRef", evidenceRef);
        d.setPayload(payload);
        return d;
    }

    @Test
    void findByStream_unknownStream_isEmpty() {
        mongoTemplate.insert(event("asset-A", 1, hash('a', 1), "batch-1"));

        assertThat(adapter.findByStream("asset-Z")).isEmpty();
    }

    /**
     * Medición del plan C, §6: la consulta real del adaptador (capturada con el profiler de Mongo) usa
     * {@code idx_stream_sequence}, sin ordenar en memoria, y solo examina las claves del stream pedido.
     */
    @Test
    void findByStream_isOneIndexScanOnIdxStreamSequence_examiningOnlyThatStreamsKeys() {
        List<TraceabilityEventDocument> events = new ArrayList<>();
        for (int s = 0; s < 50; s++) {
            for (long seq = 1; seq <= 20; seq++) {
                events.add(event("stream-" + s, seq, hash('d', (int) (s * 100 + seq)), "batch-" + s));
            }
        }
        for (long seq = 1; seq <= 6; seq++) {
            events.add(event("asset-target", seq, hash('e', (int) seq), "batch-x"));
        }
        mongoTemplate.insertAll(events);

        mongoTemplate.getDb().runCommand(new Document("profile", 2));
        assertThat(adapter.findByStream("asset-target")).hasSize(6);
        mongoTemplate.getDb().runCommand(new Document("profile", 0));

        List<Document> profiled = new ArrayList<>();
        mongoTemplate.getDb().getCollection("system.profile")
                .find(new Document("ns", mongoTemplate.getDb().getName() + ".event_store").append("op", "query"))
                .into(profiled);
        assertThat(profiled).as("una sola consulta").hasSize(1);
        Document q = profiled.get(0);
        System.out.println("[medición plan C §6] findByStream: planSummary=" + q.get("planSummary")
                + " keysExamined=" + q.get("keysExamined") + " docsExamined=" + q.get("docsExamined")
                + " hasSortStage=" + q.get("hasSortStage") + " millis=" + q.get("millis"));
        assertThat(q.getString("planSummary")).isEqualTo("IXSCAN { streamId: 1, sequence: 1 }");
        assertThat(q.get("hasSortStage")).isNotEqualTo(Boolean.TRUE);
        assertThat(((Number) q.get("keysExamined")).intValue()).isLessThanOrEqualTo(7);
        assertThat(((Number) q.get("docsExamined")).intValue()).isEqualTo(6);
    }

    private static TraceabilityEventDocument event(String streamId, long sequence, String eventHash, String batchId) {
        return TraceabilityEventDocument.builder()
                .eventId(UUID.randomUUID().toString())
                .streamId(streamId)
                .aggregateType("PhysicalAsset")
                .sequence(sequence)
                .eventType("ASSET_REGISTERED")
                .eventHash(eventHash)
                .previousHash("prev")
                .merkleBatchId(batchId)
                .build();
    }

    private static String hash(char prefix, int n) {
        String body = prefix + Integer.toHexString(n);
        return body + "0".repeat(64 - body.length());
    }
}
