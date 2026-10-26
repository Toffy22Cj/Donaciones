package com.traceability.core.infrastructure.persistence.mongo;

import com.traceability.contracts.HashPort;
import com.traceability.core.application.event.EventCanonicalMapper;
import com.traceability.core.application.event.EventPayloadRegistry;
import com.traceability.core.application.event.RecordedAtText;
import com.traceability.core.application.port.out.ActivityEventsPort.ActivityEvent;
import com.traceability.core.application.port.out.EventBatchCountPort.BatchEventCount;
import com.traceability.core.domain.event.DomainEventPayload;
import com.traceability.core.domain.fund.payloads.FundsClearedV2Payload;
import com.traceability.core.domain.physicalasset.payloads.AssetDispatchedPayload;
import com.traceability.core.domain.physicalasset.payloads.AssetReceivedPayload;
import com.traceability.core.domain.physicalasset.payloads.AssetRegisteredV3Payload;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.index.MongoPersistentEntityIndexResolver;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Puertos de salida de {@code core} del Bloque D de la ficha R-6 (plan D, §2.1, §2.2 y P-R6D-6/P-R6D-7), con Mongo real:
 * el recuento de eventos propios por lote (G-7, C-5: agregación, sin tope), la página de eventos de actividad (G-8) y
 * el índice parcial del directorio de activos.
 */
@SpringBootTest(classes = OrganizationActivityAdaptersTest.TestConfig.class)
@Testcontainers
class OrganizationActivityAdaptersTest {

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
    @Import(EventCanonicalMapper.class)
    static class TestConfig {
    }

    @Autowired private MongoTemplate mongoTemplate;
    @MockBean private HashPort hashPort;
    @Autowired private MongoEventBatchCountAdapter batchCounts;
    @Autowired private MongoActivityEventsAdapter activity;
    @Autowired private MongoAssetDirectoryAdapter assetDirectory;
    @Autowired private MongoFundDirectoryAdapter fundDirectory;

    private static final EventCanonicalMapper MAPPER = new EventCanonicalMapper();
    private static final Set<String> TYPES = Set.of("ASSET_REGISTERED", "ASSET_DISPATCHED", "ASSET_RECEIVED",
            "ASSET_DELIVERED", "ASSET_SPLIT", "FUNDS_CLEARED");

    @BeforeEach
    void setUp() {
        mongoTemplate.dropCollection(TraceabilityEventDocument.class);
        IndexOperations indexOps = mongoTemplate.indexOps(TraceabilityEventDocument.class);
        new MongoPersistentEntityIndexResolver(mongoTemplate.getConverter().getMappingContext())
                .resolveIndexFor(TraceabilityEventDocument.class).forEach(indexOps::ensureIndex);
    }

    @AfterEach
    void tearDown() {
        mongoTemplate.getDb().runCommand(new Document("profile", 0));
        mongoTemplate.dropCollection(TraceabilityEventDocument.class);
    }

    // ---- G-7: recuento por lote (EventBatchCountPort) ----

    // 10a (core): un lote que mezcla organizaciones solo cuenta los eventos de los streams pedidos
    @Test
    void countByBatch_countsOnlyTheEventsOfTheGivenStreams_withNullForTheUnbatched() {
        insert(raw("own-1", 1, "batch-mixed"));
        insert(raw("own-1", 2, "batch-mixed"));
        insert(raw("own-1", 3, null));
        insert(raw("own-2", 1, "batch-own"));
        insert(raw("other-1", 1, "batch-mixed"));
        insert(raw("other-1", 2, "batch-mixed"));
        insert(raw("other-1", 3, null));

        List<BatchEventCount> counts = batchCounts.countByBatch(List.of("own-1", "own-2"));

        assertThat(counts).containsExactlyInAnyOrder(
                new BatchEventCount("batch-mixed", 2),
                new BatchEventCount("batch-own", 1),
                new BatchEventCount(null, 1));
    }

    @Test
    void countByBatch_withoutStreams_isEmpty() {
        insert(raw("own-1", 1, "b"));
        assertThat(batchCounts.countByBatch(List.of())).isEmpty();
    }

    // ---- G-8: página de eventos de actividad (ActivityEventsPort) ----

    @Test
    void findPage_onlyTheGivenStreamsAndTypes_newestRecordedFirst_tiesByEventIdAscending_withTypedPayloads() {
        insert(event("asset-1", 1, "ASSET_REGISTERED", registered("asset-1", "loc-1"), "2026-10-20T10:00:00Z", "e-01"));
        insert(event("asset-1", 2, "ASSET_DISPATCHED", new AssetDispatchedPayload("carrier-1", "loc-1"), "2026-10-21T10:00:00Z", "e-03"));
        insert(event("asset-1", 3, "ASSET_RECEIVED", new AssetReceivedPayload("loc-2", "receiver-1"), "2026-10-21T10:00:00Z", "e-02"));
        insert(event("fund-1", 1, "FUNDS_CLEARED", cleared("fund-1"), "2026-10-19T10:00:00Z", "e-04"));
        insert(event("other", 1, "ASSET_REGISTERED", registered("other", "loc-9"), "2026-10-22T10:00:00Z", "e-05"));
        insert(event("asset-1", 4, "ASSET_DEPLETED_IGNORED", null, "2026-10-23T10:00:00Z", "e-06"));

        List<ActivityEvent> page = activity.findPage(List.of("asset-1", "fund-1"), TYPES, null, null, 10);

        assertThat(page).extracting(ActivityEvent::eventId).containsExactly("e-02", "e-03", "e-01", "e-04");
        ActivityEvent received = page.get(0);
        assertThat(received.streamId()).isEqualTo("asset-1");
        assertThat(received.eventType()).isEqualTo("ASSET_RECEIVED");
        assertThat(received.recordedAt()).isEqualTo("2026-10-21T10:00:00Z");
        assertThat(received.occurredAt()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
        assertThat(received.payload()).isEqualTo(new AssetReceivedPayload("loc-2", "receiver-1"));
        assertThat(page.get(2).payload()).isInstanceOf(AssetRegisteredV3Payload.class);
    }

    @Test
    void findPage_afterACursor_continuesWithoutRepeatingNorSkipping_includingTiesOfTheSameInstant() {
        insert(event("asset-1", 1, "ASSET_REGISTERED", registered("asset-1", "l"), "2026-10-21T10:00:00Z", "e-1"));
        insert(event("asset-1", 2, "ASSET_DISPATCHED", new AssetDispatchedPayload("c", "l"), "2026-10-21T10:00:00Z", "e-2"));
        insert(event("asset-1", 3, "ASSET_RECEIVED", new AssetReceivedPayload("l2", "r"), "2026-10-21T10:00:00Z", "e-3"));
        insert(event("asset-1", 4, "ASSET_RECEIVED", new AssetReceivedPayload("l3", "r"), "2026-10-20T10:00:00Z", "e-4"));

        // Después de (10:00 del 21, e-1): los empates restantes de ese instante y luego los anteriores
        assertThat(activity.findPage(List.of("asset-1"), TYPES, "2026-10-21T10:00:00Z", "e-1", 10))
                .extracting(ActivityEvent::eventId).containsExactly("e-2", "e-3", "e-4");
        // Estrictamente antes de un instante (sin id: el cursor es de otra fuente que va después en el empate)
        assertThat(activity.findPage(List.of("asset-1"), TYPES, "2026-10-21T10:00:00Z", null, 10))
                .extracting(ActivityEvent::eventId).containsExactly("e-4");
        // El límite
        assertThat(activity.findPage(List.of("asset-1"), TYPES, null, null, 2))
                .extracting(ActivityEvent::eventId).containsExactly("e-1", "e-2");
    }

    @Test
    void findPage_withoutStreams_isEmpty() {
        insert(event("asset-1", 1, "ASSET_REGISTERED", registered("asset-1", "l"), "2026-10-21T10:00:00Z", "e-1"));
        assertThat(activity.findPage(List.of(), TYPES, null, null, 10)).isEmpty();
    }

    // ---- P-R6D-6: índice parcial del directorio de activos ----

    @Test
    void assetDirectory_usesThePartialIndexOfAssetGenesis_examiningOnlyThatOrganizationsGenesis() {
        List<TraceabilityEventDocument> docs = new ArrayList<>();
        for (int org = 0; org < 20; org++) {
            for (int a = 0; a < 10; a++) {
                String asset = "asset-" + org + "-" + a;
                docs.add(event(asset, 1, "ASSET_REGISTERED", registeredFor(asset, "org-" + org),
                        "2026-10-20T10:00:0" + (a % 10) + "Z", UUID.randomUUID().toString()));
                docs.add(event(asset, 2, "ASSET_DISPATCHED", new AssetDispatchedPayload("c", "l"),
                        "2026-10-21T10:00:00Z", UUID.randomUUID().toString()));
            }
        }
        mongoTemplate.insertAll(docs);

        mongoTemplate.getDb().runCommand(new Document("profile", 2));
        List<String> ids = assetDirectory.findAssetIdsByOrganization("org-7", 100);
        mongoTemplate.getDb().runCommand(new Document("profile", 0));

        assertThat(ids).hasSize(10).allMatch(id -> id.startsWith("asset-7-"));
        List<Document> profiled = new ArrayList<>();
        // La clave del filtro es literalmente "payload.organizationRef": se filtra en Java, no con una ruta anidada
        mongoTemplate.getDb().getCollection("system.profile")
                .find(new Document("ns", mongoTemplate.getDb().getName() + ".event_store").append("op", "query"))
                .forEach(d -> {
                    Document filter = d.get("command", Document.class).get("filter", Document.class);
                    if (filter != null && "org-7".equals(filter.get("payload.organizationRef"))) {
                        profiled.add(d);
                    }
                });
        assertThat(profiled).hasSize(1);
        Document q = profiled.get(0);
        System.out.println("[medición plan D, P-R6D-6] directorio de activos: planSummary=" + q.get("planSummary")
                + " keysExamined=" + q.get("keysExamined") + " docsExamined=" + q.get("docsExamined")
                + " hasSortStage=" + q.get("hasSortStage"));
        assertThat(q.getString("planSummary")).contains("payload.organizationRef: 1, recordedAt: 1, streamId: 1");
        assertThat(((Number) q.get("docsExamined")).intValue()).isEqualTo(10);
        assertThat(q.get("hasSortStage")).isNotEqualTo(Boolean.TRUE);
    }

    // ---- DT-B-1 (plan de las deudas 1 a 3 §2): con el texto fijo, el directorio ordena por tiempo dentro del segundo ----

    @Test
    void dtb1_assetDirectory_withTheFixedText_ordersTwoGenesesOfTheSameSecondChronologically() {
        // el streamId va al revés que el tiempo, para que el desempate no disimule el orden de recordedAt
        mongoTemplate.insertAll(List.of(
                event("asset-z", 1, "ASSET_REGISTERED", registeredFor("asset-z", "org-dtb1"),
                        RecordedAtText.of(Instant.parse("2026-10-20T10:00:05Z")), UUID.randomUUID().toString()),
                event("asset-a", 1, "ASSET_REGISTERED", registeredFor("asset-a", "org-dtb1"),
                        RecordedAtText.of(Instant.parse("2026-10-20T10:00:05.1Z")), UUID.randomUUID().toString())));

        assertThat(assetDirectory.findAssetIdsByOrganization("org-dtb1", 10)).containsExactly("asset-z", "asset-a");
    }

    // ---- P-E2-2 (Enmienda 3 de la ficha R-6; plan de las deudas 1 a 3 §3): los directorios, de lo más reciente ----

    @Test
    void pe2_latestDirectories_newestGenesisFirst_tiesByStreamIdDescending_withTheLimit() {
        mongoTemplate.insertAll(List.of(
                event("asset-9", 1, "ASSET_REGISTERED", registeredFor("asset-9", "org-x"), "2026-10-20T10:00:00.000000000Z", "e-a9"),
                event("asset-1", 1, "ASSET_REGISTERED", registeredFor("asset-1", "org-x"), "2026-10-20T10:00:01.000000000Z", "e-a1"),
                event("asset-2", 1, "ASSET_REGISTERED", registeredFor("asset-2", "org-x"), "2026-10-20T10:00:02.000000000Z", "e-a2"),
                event("asset-3", 1, "ASSET_REGISTERED", registeredFor("asset-3", "org-x"), "2026-10-20T10:00:02.000000000Z", "e-a3"),
                event("asset-1", 2, "ASSET_DISPATCHED", new AssetDispatchedPayload("c", "l"), "2026-10-21T10:00:00.000000000Z", "e-a1b"),
                event("asset-0", 1, "ASSET_REGISTERED", registeredFor("asset-0", "org-other"), "2026-10-22T10:00:00.000000000Z", "e-a0"),
                event("fund-9", 1, "FUNDS_CLEARED", cleared("fund-9"), "2026-10-20T10:00:00.000000000Z", "e-f9"),
                event("fund-1", 1, "FUNDS_CLEARED", cleared("fund-1"), "2026-10-20T10:00:02.000000000Z", "e-f1"),
                event("fund-2", 1, "FUNDS_CLEARED", cleared("fund-2"), "2026-10-20T10:00:02.000000000Z", "e-f2")));

        assertThat(assetDirectory.findLatestAssetIdsByOrganization("org-x", 10))
                .containsExactly("asset-3", "asset-2", "asset-1", "asset-9");
        assertThat(assetDirectory.findLatestAssetIdsByOrganization("org-x", 2)).containsExactly("asset-3", "asset-2");
        assertThat(fundDirectory.findLatestFundIdsByOrganization("org-1", 10)).containsExactly("fund-2", "fund-1", "fund-9");
        assertThat(fundDirectory.findLatestFundIdsByOrganization("org-1", 1)).containsExactly("fund-2");
        // los métodos de antes no cambian: de lo más antiguo
        assertThat(assetDirectory.findAssetIdsByOrganization("org-x", 10))
                .containsExactly("asset-9", "asset-1", "asset-2", "asset-3");
        assertThat(fundDirectory.findFundIdsByOrganization("org-1", 10)).containsExactly("fund-9", "fund-1", "fund-2");
    }

    // P-PE2-2: el orden descendente recorre el índice de génesis de hoy, sin etapa de ordenación
    @Test
    void pe2_latestDirectories_useTheGenesisIndexes_withoutASortStage() {
        List<TraceabilityEventDocument> docs = new ArrayList<>();
        for (int org = 0; org < 5; org++) {
            for (int a = 0; a < 10; a++) {
                String asset = "asset-" + org + "-" + a;
                docs.add(event(asset, 1, "ASSET_REGISTERED", registeredFor(asset, "org-" + org),
                        "2026-10-20T10:00:0" + a + ".000000000Z", UUID.randomUUID().toString()));
                String fund = "fund-" + org + "-" + a;
                docs.add(event(fund, 1, "FUNDS_CLEARED", new FundsClearedV2Payload("org-" + org, 1000L, "tx-" + fund,
                        "COP", "camp-1", "anon:x"), "2026-10-20T10:00:0" + a + ".000000000Z", UUID.randomUUID().toString()));
            }
        }
        mongoTemplate.insertAll(docs);

        for (String directory : List.of("asset", "fund")) {
            mongoTemplate.getDb().getCollection("system.profile").drop();
            mongoTemplate.getDb().runCommand(new Document("profile", 2));
            List<String> ids = directory.equals("asset") ? assetDirectory.findLatestAssetIdsByOrganization("org-3", 100)
                    : fundDirectory.findLatestFundIdsByOrganization("org-3", 100);
            mongoTemplate.getDb().runCommand(new Document("profile", 0));

            assertThat(ids).hasSize(10).allMatch(id -> id.startsWith(directory + "-3-"));
            List<Document> profiled = new ArrayList<>();
            mongoTemplate.getDb().getCollection("system.profile")
                    .find(new Document("ns", mongoTemplate.getDb().getName() + ".event_store").append("op", "query"))
                    .forEach(d -> {
                        Document filter = d.get("command", Document.class).get("filter", Document.class);
                        if (filter != null && "org-3".equals(filter.get("payload.organizationRef"))) {
                            profiled.add(d);
                        }
                    });
            assertThat(profiled).as(directory).hasSize(1);
            Document q = profiled.get(0);
            System.out.println("[medición P-PE2-2] directorio " + directory + " descendente: planSummary="
                    + q.get("planSummary") + " keysExamined=" + q.get("keysExamined") + " docsExamined="
                    + q.get("docsExamined") + " hasSortStage=" + q.get("hasSortStage"));
            assertThat(q.getString("planSummary")).as(directory)
                    .contains("payload.organizationRef: 1, recordedAt: 1, streamId: 1");
            assertThat(((Number) q.get("docsExamined")).intValue()).as(directory).isEqualTo(10);
            assertThat(q.get("hasSortStage")).as(directory).isNotEqualTo(Boolean.TRUE);
        }
    }

    // ---- Utilidades ----

    private void insert(TraceabilityEventDocument doc) {
        mongoTemplate.insert(doc);
    }

    private static TraceabilityEventDocument raw(String stream, long sequence, String batchId) {
        return TraceabilityEventDocument.builder().eventId(UUID.randomUUID().toString()).streamId(stream)
                .aggregateType("PhysicalAsset").sequence(sequence).eventType("ASSET_DISPATCHED")
                .recordedAt("2026-10-20T10:00:00Z").merkleBatchId(batchId).build();
    }

    private static AssetRegisteredV3Payload registered(String assetId, String location) {
        return registeredFor(assetId, "org-1", location);
    }

    private static AssetRegisteredV3Payload registeredFor(String assetId, String org) {
        return registeredFor(assetId, org, "loc-1");
    }

    private static AssetRegisteredV3Payload registeredFor(String assetId, String org, String location) {
        return new AssetRegisteredV3Payload(assetId, "BLANKET", new BigDecimal("10"), "UNITS", location, "cust-1",
                null, assetId, "alloc-1", null, org, "anon:x", "don-1", "camp-1");
    }

    private static FundsClearedV2Payload cleared(String fundId) {
        return new FundsClearedV2Payload("org-1", 1000L, "tx-" + fundId, "COP", "camp-1", "anon:x");
    }

    private static TraceabilityEventDocument event(String stream, long sequence, String type, DomainEventPayload payload,
                                                   String recordedAt, String eventId) {
        String aggregate = stream.startsWith("fund") ? "Fund" : "PhysicalAsset";
        Map<String, Object> payloadMap = null;
        String schema = null;
        if (payload != null) {
            schema = EventPayloadRegistry.getSchemaVersionForClass(payload.getClass());
            @SuppressWarnings("unchecked")
            Map<String, Object> p = (Map<String, Object>) MAPPER.toCanonicalMap(eventId, stream, aggregate, sequence, type,
                    schema, Instant.parse("2026-10-01T00:00:00Z"), Instant.parse(recordedAt), "TEST", payload).get("payload");
            payloadMap = p;
        }
        return TraceabilityEventDocument.builder().eventId(eventId).streamId(stream).aggregateType(aggregate)
                .sequence(sequence).eventType(type).schemaVersion(schema).occurredAt("2026-10-01T00:00:00Z")
                .recordedAt(recordedAt).payload(payloadMap).eventHash("h").previousHash("p").build();
    }
}
