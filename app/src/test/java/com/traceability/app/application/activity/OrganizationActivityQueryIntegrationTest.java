package com.traceability.app.application.activity;

import com.traceability.app.application.activity.OrganizationActivityQuery.ActivityItem;
import com.traceability.app.application.activity.OrganizationActivityQuery.ActivityKey;
import com.traceability.app.application.activity.OrganizationActivityQuery.ActivityPage;
import com.traceability.app.config.TraceabilityInfrastructureConfig;
import com.traceability.contracts.SequenceRange;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.contracts.authorization.AuthorizationRole;
import com.traceability.convocatoria.application.query.OrganizationCampaignClosuresQuery;
import com.traceability.convocatoria.application.query.OrganizationCampaignClosuresQuery.CampaignClosure;
import com.traceability.core.application.authorization.OrganizationBoundaryPolicy;
import com.traceability.core.application.event.EventCanonicalMapper;
import com.traceability.core.application.event.EventPayloadRegistry;
import com.traceability.core.application.event.RecordedAtText;
import com.traceability.core.application.port.out.ActivityEventsPort;
import com.traceability.core.application.port.out.AssetDirectoryPort;
import com.traceability.core.application.port.out.EventBatchCountPort;
import com.traceability.core.application.port.out.FundDirectoryPort;
import com.traceability.core.application.service.LocationReferenceService;
import com.traceability.core.domain.event.DomainEventPayload;
import com.traceability.core.domain.fund.payloads.FundsClearedV2Payload;
import com.traceability.core.domain.physicalasset.payloads.AssetDeliveredPayload;
import com.traceability.core.domain.physicalasset.payloads.AssetDispatchedPayload;
import com.traceability.core.domain.physicalasset.payloads.AssetReceivedPayload;
import com.traceability.core.domain.physicalasset.payloads.AssetRegisteredV3Payload;
import com.traceability.core.domain.physicalasset.payloads.AssetSplitV3Payload;
import com.traceability.core.infrastructure.persistence.mongo.TraceabilityEventDocument;
import com.traceability.crypto.application.port.out.MerkleBatchRepositoryPort;
import com.traceability.crypto.domain.AnchorStatus;
import com.traceability.crypto.domain.MerkleBatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Ficha R-6, G-8 (plan D, §2.2; tests 11a, 11b y 11d) con Mongo real: tres fuentes (eventos del Event Store, cierres de
 * convocatoria y lotes anclados) mezcladas con el orden total {@code (instante descendente, tipo de fuente, id)}, 20
 * por página. Los eventos se escriben a mano con su payload real (forma canónica) y un {@code recordedAt} fijo.
 */
@SpringBootTest(classes = OrganizationActivityQueryIntegrationTest.TestConfig.class, properties = {"spring.ai.openai.api-key=dummy"})
@Testcontainers
class OrganizationActivityQueryIntegrationTest {

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
            EventCanonicalMapper.class})
    static class TestConfig {
    }

    @Container
    static MongoDBContainer mongoDBContainer = new MongoDBContainer(org.testcontainers.utility.DockerImageName.parse("mongo:6.0"))
            .withCommand("--replSet", "rs0");

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongoDBContainer::getReplicaSetUrl);
    }

    private static final String ORG = "org-A";
    private static final EventCanonicalMapper MAPPER = new EventCanonicalMapper();

    @Autowired private MongoTemplate mongoTemplate;
    @Autowired private MerkleBatchRepositoryPort batches;
    @Autowired private FundDirectoryPort funds;
    @Autowired private AssetDirectoryPort assets;
    @Autowired private ActivityEventsPort events;
    @Autowired private EventBatchCountPort counts;

    private OrganizationCampaignClosuresQuery closures;
    private OrganizationActivityQuery query;

    @BeforeEach
    void setUp() {
        mongoTemplate.dropCollection("event_store");
        mongoTemplate.dropCollection("merkle_batches");
        mongoTemplate.createCollection("event_store");
        mongoTemplate.createCollection("merkle_batches");
        closures = mock(OrganizationCampaignClosuresQuery.class);
        when(closures.closedCampaigns(anyString())).thenReturn(List.of());
        LocationReferenceService locations = mock(LocationReferenceService.class);
        when(locations.resolveZone(anyString())).thenAnswer(i -> Optional.of("Zona de " + i.getArgument(0)));
        query = new OrganizationActivityQuery(funds, assets, events, counts, batches, closures, locations,
                new OrganizationBoundaryPolicy());
    }

    private static AuthorizationPrincipal employee() {
        return new AuthorizationPrincipal("acct-1", ORG, Set.of(AuthorizationRole.EMPLOYEE), null);
    }

    private void insert(String stream, long sequence, String type, DomainEventPayload payload, String recordedAt,
                        String eventId, String batchId) {
        String aggregate = stream.startsWith("fund") ? "Fund" : "PhysicalAsset";
        String schema = EventPayloadRegistry.getSchemaVersionForClass(payload.getClass());
        Instant occurred = Instant.parse(recordedAt).minusSeconds(60);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) MAPPER.toCanonicalMap(eventId, stream, aggregate, sequence, type,
                schema, occurred, Instant.parse(recordedAt), "TEST", payload).get("payload");
        mongoTemplate.insert(TraceabilityEventDocument.builder().eventId(eventId).streamId(stream).aggregateType(aggregate)
                .sequence(sequence).eventType(type).schemaVersion(schema).occurredAt(occurred.toString())
                .recordedAt(recordedAt).payload(map).merkleBatchId(batchId).eventHash("h").previousHash("p").build());
    }

    private static AssetRegisteredV3Payload registered(String assetId, String org) {
        return new AssetRegisteredV3Payload(assetId, "BLANKET", new BigDecimal("10"), "UNITS", "warehouse-1", "cust-1",
                null, assetId, "alloc-1", null, org, "anon:secret-donor", "don-1", "camp-1");
    }

    private void anchored(String batchId, Instant anchoredAt) {
        batches.save(new MerkleBatch(batchId, Map.of("x", new SequenceRange(1, 1)), "root-" + batchId, List.of("h"),
                Instant.parse("2026-10-01T00:00:00Z"), AnchorStatus.ANCHORED, "sepolia", "0xc", 1L, "0xtx-" + batchId,
                Instant.parse("2026-10-01T01:00:00Z"), anchoredAt, 9L, null));
    }

    private List<ActivityItem> allPages() {
        List<ActivityItem> all = new ArrayList<>();
        ActivityKey after = null;
        int pages = 0;
        do {
            ActivityPage page = query.page(employee(), ORG, after);
            if (page.next() != null) {
                assertThat(page.items()).as("una página intermedia trae 20").hasSize(OrganizationActivityQuery.PAGE_SIZE);
            }
            all.addAll(page.items());
            after = page.next();
            pages++;
            assertThat(pages).as("termina").isLessThan(50);
        } while (after != null);
        return all;
    }

    private static String signature(ActivityItem i) {
        return i.type() + "|" + (i.subject() == null ? "-" : i.subject().kind() + ":" + i.subject().ref()) + "|"
                + i.occurredAt() + "|" + i.eventCount();
    }

    // 11a y 11d: todas las páginas, de lo más reciente a lo más antiguo, sin repeticiones ni saltos; nada de otra org
    @Test
    void t11a_allPagesNewestFirst_withoutRepetitionsNorGaps_andNothingOfAnotherOrganization() {
        insert("fund-1", 1, "FUNDS_CLEARED", new FundsClearedV2Payload(ORG, 5000L, "tx-1", "COP", "camp-1", "anon:secret-donor"),
                "2026-10-10T00:00:00Z", "e-000", "b-1");
        List<String> expected = new ArrayList<>();
        // 5 activos con 6 eventos cada uno, con instantes que se repiten entre activos (empates dentro de la fuente)
        for (int a = 1; a <= 5; a++) {
            String asset = "asset-" + a;
            insert(asset, 1, "ASSET_REGISTERED", registered(asset, ORG), "2026-10-11T0" + a + ":00:00Z", "e-" + a + "1", "b-1");
            insert(asset, 2, "ASSET_DISPATCHED", new AssetDispatchedPayload("carrier-secret", "warehouse-1"),
                    "2026-10-12T00:00:00Z", "e-" + a + "2", "b-2");
            insert(asset, 3, "ASSET_RECEIVED", new AssetReceivedPayload("site-" + a, "receiver-secret"),
                    "2026-10-13T00:00:00Z", "e-" + a + "3", null);
            insert(asset, 4, "ASSET_SPLIT", new AssetSplitV3Payload("child-" + a, new BigDecimal("2"), "UNITS",
                    new BigDecimal("10"), new BigDecimal("8"), "RECEIVED", "site-" + a, "cust-secret", asset, ORG,
                    "anon:secret-donor", "don-1", "camp-1"), "2026-10-14T00:00:00Z", "e-" + a + "4", null);
            insert(asset, 5, "ASSET_DELIVERED", new AssetDeliveredPayload("final-secret", "beneficiary-secret", "site-" + a,
                    "sha256:" + "0".repeat(64), Instant.parse("2026-10-15T00:00:00Z")), "2026-10-15T00:00:00Z", "e-" + a + "5", null);
            insert(asset, 6, "ASSET_DEPLETED", new com.traceability.core.domain.physicalasset.payloads.AssetDepletedPayload(
                    new BigDecimal("8")), "2026-10-16T00:00:00Z", "e-" + a + "6", null);
        }
        // Otra organización, con los mismos instantes: nunca aparece (11d)
        insert("asset-other", 1, "ASSET_REGISTERED", registered("asset-other", "org-B"), "2026-10-12T00:00:00Z", "e-x1", "b-2");
        // Cierres (uno empata con los despachos) y lotes anclados con eventos propios (uno empata con las recepciones)
        when(closures.closedCampaigns(ORG)).thenReturn(List.of(
                new CampaignClosure("camp-1", Instant.parse("2026-10-12T00:00:00Z")),
                new CampaignClosure("camp-2", Instant.parse("2026-10-01T00:00:00Z"))));
        anchored("b-1", Instant.parse("2026-10-13T00:00:00Z"));
        anchored("b-2", Instant.parse("2026-10-17T00:00:00Z"));

        List<ActivityItem> all = allPages();

        // 1 + 5×5 eventos de la lista (ASSET_DEPLETED no es de D-R6-9) + 2 cierres + 2 lotes
        assertThat(all).hasSize(1 + 25 + 2 + 2);
        assertThat(new HashSet<>(all.stream().map(OrganizationActivityQueryIntegrationTest::signature).toList()))
                .as("sin repeticiones").hasSize(all.size());
        assertThat(all).noneMatch(i -> i.subject() != null && i.subject().ref().equals("asset-other"));
        assertThat(all).noneMatch(i -> i.type().equals("ASSET_DEPLETED"));
        // Orden: lo más reciente primero; en un empate, eventos, después cierres y después lotes
        // b-2: los 5 despachos propios; el evento de la otra organización del mismo lote no cuenta
        assertThat(signature(all.get(0))).isEqualTo("BATCH_ANCHORED|-|2026-10-17T00:00:00Z|5");
        List<String> types = all.stream().map(ActivityItem::type).toList();
        int lastReceived = types.lastIndexOf("ASSET_RECEIVED");
        assertThat(types.get(lastReceived + 1)).isEqualTo("BATCH_ANCHORED");
        assertThat(signature(all.get(lastReceived + 1))).isEqualTo("BATCH_ANCHORED|-|2026-10-13T00:00:00Z|6");
        int lastDispatched = types.lastIndexOf("ASSET_DISPATCHED");
        assertThat(signature(all.get(lastDispatched + 1))).isEqualTo("CAMPAIGN_CLOSED|CAMPAIGN:camp-1|2026-10-12T00:00:00Z|null");
        assertThat(signature(all.get(all.size() - 1))).isEqualTo("CAMPAIGN_CLOSED|CAMPAIGN:camp-2|2026-10-01T00:00:00Z|null");
        // El orden por instante de listado es descendente en toda la lista
        List<Instant> listed = all.stream().map(OrganizationActivityQueryIntegrationTest::listedAt).toList();
        assertThat(listed).isSortedAccordingTo(Comparator.reverseOrder());
    }

    /** El instante de listado: recordedAt para los eventos (aquí occurredAt + 60 s), closedAt y anchoredAt. */
    private static Instant listedAt(ActivityItem i) {
        return i.type().startsWith("ASSET_") || i.type().equals("FUNDS_CLEARED") ? i.occurredAt().plusSeconds(60) : i.occurredAt();
    }

    // 11b: empate de instantes entre fuentes distintas justo en el borde de la página
    @Test
    void t11b_aTieBetweenSourcesOnThePageBoundary_isNeitherRepeatedNorSkipped() {
        insert("asset-1", 1, "ASSET_REGISTERED", registered("asset-1", ORG), "2026-10-01T00:00:00Z", "e-old", "b-tie");
        // 19 eventos más recientes que el empate
        for (int i = 1; i <= 19; i++) {
            insert("asset-1", 1 + i, "ASSET_RECEIVED", new AssetReceivedPayload("site", "r"),
                    String.format("2026-10-20T00:00:%02dZ", i), String.format("e-new-%02d", i), null);
        }
        // En el mismo instante T: un evento (fuente 1.ª, ítem 20), un cierre (2.ª, ítem 21) y un lote anclado (3.ª, 22)
        String t = "2026-10-10T00:00:00Z";
        insert("asset-1", 21, "ASSET_RECEIVED", new AssetReceivedPayload("site-t", "r"), t, "e-tie", null);
        when(closures.closedCampaigns(ORG)).thenReturn(List.of(new CampaignClosure("camp-tie", Instant.parse(t))));
        anchored("b-tie", Instant.parse(t));

        ActivityPage first = query.page(employee(), ORG, null);
        ActivityPage second = query.page(employee(), ORG, first.next());

        assertThat(first.items()).hasSize(20);
        assertThat(signature(first.items().get(19))).isEqualTo("ASSET_RECEIVED|ASSET:asset-1|2026-10-09T23:59:00Z|null");
        assertThat(first.next()).isNotNull();
        assertThat(second.items()).extracting(OrganizationActivityQueryIntegrationTest::signature).containsExactly(
                "CAMPAIGN_CLOSED|CAMPAIGN:camp-tie|2026-10-10T00:00:00Z|null",
                "BATCH_ANCHORED|-|2026-10-10T00:00:00Z|1",
                "ASSET_REGISTERED|ASSET:asset-1|2026-09-30T23:59:00Z|null");
        assertThat(second.next()).isNull();
    }

    // 11b (bis): el borde de página cae en un cierre que empata con un evento anterior en el orden: ni ese evento ni el
    // cierre se repiten en la página siguiente (el cursor de otra fuente solo deja pasar eventos más antiguos)
    @Test
    void t11b_aPageThatEndsOnAClosureTiedWithAnEvent_repeatsNeitherOnTheNextPage() {
        insert("asset-1", 1, "ASSET_REGISTERED", registered("asset-1", ORG), "2026-10-01T00:00:00Z", "e-old", null);
        for (int i = 1; i <= 18; i++) {
            insert("asset-1", 1 + i, "ASSET_RECEIVED", new AssetReceivedPayload("site", "r"),
                    String.format("2026-10-20T00:00:%02dZ", i), String.format("e-new-%02d", i), null);
        }
        String t = "2026-10-10T00:00:00Z";
        // "e-zz" > "camp-a" como texto: si el filtro de eventos usara el id de otra fuente, volvería a salir
        insert("asset-1", 20, "ASSET_RECEIVED", new AssetReceivedPayload("site-t", "r"), t, "e-zz", null);
        when(closures.closedCampaigns(ORG)).thenReturn(List.of(new CampaignClosure("camp-a", Instant.parse(t))));

        ActivityPage first = query.page(employee(), ORG, null);
        ActivityPage second = query.page(employee(), ORG, first.next());

        assertThat(first.items()).hasSize(20);
        assertThat(signature(first.items().get(18))).isEqualTo("ASSET_RECEIVED|ASSET:asset-1|2026-10-09T23:59:00Z|null");
        assertThat(signature(first.items().get(19))).isEqualTo("CAMPAIGN_CLOSED|CAMPAIGN:camp-a|2026-10-10T00:00:00Z|null");
        assertThat(second.items()).extracting(OrganizationActivityQueryIntegrationTest::signature)
                .containsExactly("ASSET_REGISTERED|ASSET:asset-1|2026-09-30T23:59:00Z|null");
        assertThat(second.next()).isNull();
    }

    // DT-B-1 (plan de las deudas 1 a 3 §2; P-DT1-3): un evento nuevo, con 9 decimales, 100 ms después de un cierre o de
    // un anclaje en el segundo exacto, sale antes (de lo más reciente a lo más antiguo). Con Instant.toString() en el
    // cierre o el anclaje, su "…:05Z" quedaba por delante del "…:05.100000000Z" del evento
    @Test
    void dtb1_aNewEvent100msAfterAClosureOrAnAnchoringOfTheExactSecond_comesFirst() {
        insert("asset-1", 1, "ASSET_REGISTERED", registered("asset-1", ORG), "2026-10-01T00:00:00Z", "e-old", "b-dtb1");
        insert("asset-1", 2, "ASSET_RECEIVED", new AssetReceivedPayload("site", "r"), "2026-10-10T00:00:05.100000000Z",
                "e-new", null);
        when(closures.closedCampaigns(ORG)).thenReturn(List.of(new CampaignClosure("camp-a",
                Instant.parse("2026-10-10T00:00:05Z"))));

        assertThat(query.page(employee(), ORG, null).items()).extracting(ActivityItem::type)
                .startsWith("ASSET_RECEIVED", "CAMPAIGN_CLOSED");

        when(closures.closedCampaigns(ORG)).thenReturn(List.of());
        anchored("b-dtb1", Instant.parse("2026-10-10T00:00:05Z"));

        assertThat(query.page(employee(), ORG, null).items()).extracting(ActivityItem::type)
                .startsWith("ASSET_RECEIVED", "BATCH_ANCHORED");
    }

    // P-E2-2 (Enmienda 3 de la ficha R-6; plan de las deudas 1 a 3 §3): con 10 001 activos o fondos, G-8 lee los 10 000
    // más recientes por su génesis. La primera página empieza por el más nuevo, que antes quedaba fuera; truncated sigue
    @Test
    void pe2_with10001Assets_theNewestAssetLeadsTheFirstPage_andItIsTruncated() {
        manyGeneses("asset-", OrganizationActivityQuery.DIRECTORY_LIMIT + 1);

        ActivityPage page = query.page(employee(), ORG, null);

        assertThat(page.truncated()).isTrue();
        assertThat(signature(page.items().get(0))).startsWith("ASSET_REGISTERED|ASSET:asset-10000|");
        assertThat(page.items()).extracting(OrganizationActivityQueryIntegrationTest::signature)
                .noneMatch(s -> s.contains("ASSET:asset-0|"));
    }

    @Test
    void pe2_with10001Funds_theNewestFundLeadsTheFirstPage_andItIsTruncated() {
        manyGeneses("fund-", OrganizationActivityQuery.DIRECTORY_LIMIT + 1);

        ActivityPage page = query.page(employee(), ORG, null);

        assertThat(page.truncated()).isTrue();
        assertThat(signature(page.items().get(0))).startsWith("FUNDS_CLEARED|FUND:fund-10000|");
    }

    /** {@code n} génesis de la organización, una por segundo: la {@code prefix + (n - 1)} es la más reciente. */
    private void manyGeneses(String prefix, int n) {
        Instant base = Instant.parse("2026-09-01T00:00:00Z");
        List<TraceabilityEventDocument> docs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String stream = prefix + i;
            boolean fund = prefix.startsWith("fund");
            DomainEventPayload payload = fund
                    ? new FundsClearedV2Payload(ORG, 1000L, "tx-" + stream, "COP", "camp-1", "anon:x")
                    : registered(stream, ORG);
            String type = fund ? "FUNDS_CLEARED" : "ASSET_REGISTERED";
            String schema = EventPayloadRegistry.getSchemaVersionForClass(payload.getClass());
            Instant at = base.plusSeconds(i);
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) MAPPER.toCanonicalMap("e-" + stream, stream,
                    fund ? "Fund" : "PhysicalAsset", 1, type, schema, at, at, "TEST", payload).get("payload");
            docs.add(TraceabilityEventDocument.builder().eventId("e-" + stream).streamId(stream)
                    .aggregateType(fund ? "Fund" : "PhysicalAsset").sequence(1).eventType(type).schemaVersion(schema)
                    .occurredAt(at.toString()).recordedAt(RecordedAtText.of(at)).payload(map).eventHash("h")
                    .previousHash("p").build());
        }
        mongoTemplate.insertAll(docs);
    }

    // Ítems: cantidad, unidad y zona salen del payload; un FUNDS_CLEARED no lleva importe
    @Test
    void items_carryQuantityUnitAndZoneFromThePayload_andAClearingCarriesNoAmount() {
        insert("asset-1", 1, "ASSET_REGISTERED", registered("asset-1", ORG), "2026-10-03T00:00:00Z", "e-1", null);
        insert("asset-1", 2, "ASSET_DISPATCHED", new AssetDispatchedPayload("carrier", "warehouse-1"), "2026-10-04T00:00:00Z", "e-2", null);
        insert("asset-1", 3, "ASSET_RECEIVED", new AssetReceivedPayload("site-9", "r"), "2026-10-05T00:00:00Z", "e-3", null);
        insert("asset-1", 4, "ASSET_SPLIT", new AssetSplitV3Payload("child-1", new BigDecimal("2.5"), "KG",
                new BigDecimal("10"), new BigDecimal("7.5"), "RECEIVED", "site-9", "c", "asset-1", ORG, "anon:x", "d", "c"),
                "2026-10-06T00:00:00Z", "e-4", null);
        insert("asset-1", 5, "ASSET_DELIVERED", new AssetDeliveredPayload("f", "b", "site-7", "sha256:" + "1".repeat(64),
                Instant.parse("2026-10-07T00:00:00Z")), "2026-10-07T00:00:00Z", "e-5", null);
        insert("fund-1", 1, "FUNDS_CLEARED", new FundsClearedV2Payload(ORG, 5000L, "tx", "COP", "camp-1", "anon:x"),
                "2026-10-02T00:00:00Z", "e-6", null);

        List<ActivityItem> items = query.page(employee(), ORG, null).items();

        assertThat(items).extracting(ActivityItem::type).containsExactly("ASSET_DELIVERED", "ASSET_SPLIT",
                "ASSET_RECEIVED", "ASSET_DISPATCHED", "ASSET_REGISTERED", "FUNDS_CLEARED");
        ActivityItem delivered = items.get(0);
        assertThat(delivered.locationZone()).isEqualTo("Zona de site-7");
        assertThat(delivered.quantity()).isNull();
        ActivityItem split = items.get(1);
        assertThat(split.quantity()).isEqualByComparingTo("2.5");
        assertThat(split.unitOfMeasure()).isEqualTo("KG");
        assertThat(split.locationZone()).isNull();
        assertThat(items.get(2).locationZone()).isEqualTo("Zona de site-9");
        assertThat(items.get(3).locationZone()).isNull();
        ActivityItem registeredItem = items.get(4);
        assertThat(registeredItem.quantity()).isEqualByComparingTo("10");
        assertThat(registeredItem.unitOfMeasure()).isEqualTo("UNITS");
        assertThat(registeredItem.locationZone()).isEqualTo("Zona de warehouse-1");
        ActivityItem clearing = items.get(5);
        assertThat(clearing.subject().kind()).isEqualTo("FUND");
        assertThat(clearing.subject().ref()).isEqualTo("fund-1");
        assertThat(clearing.quantity()).isNull();
        assertThat(clearing.unitOfMeasure()).isNull();
        assertThat(clearing.eventCount()).isNull();
        assertThat(items).allSatisfy(i -> assertThat(i.occurredAt()).isNotNull());
    }
}
