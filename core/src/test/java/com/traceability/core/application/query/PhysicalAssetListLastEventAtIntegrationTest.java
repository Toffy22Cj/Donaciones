package com.traceability.core.application.query;

import com.traceability.contracts.HashPort;
import com.traceability.contracts.authorization.AuthorizationRole;
import com.traceability.core.application.port.out.EventStorePort;
import com.traceability.core.application.port.out.PhysicalAssetOperationalReadPort;
import com.traceability.core.application.port.out.PhysicalAssetOperationalView;
import com.traceability.core.domain.event.DomainEvent;
import com.traceability.core.domain.event.DomainEventPayload;
import com.traceability.core.domain.event.HumanActor;
import com.traceability.core.domain.event.SystemActor;
import com.traceability.core.domain.physicalasset.PhysicalAssetEventType;
import com.traceability.core.domain.physicalasset.payloads.AssetDispatchedPayload;
import com.traceability.core.domain.physicalasset.payloads.AssetReceivedPayload;
import com.traceability.core.domain.physicalasset.payloads.AssetRegisteredV3Payload;
import com.traceability.core.infrastructure.authorization.TestIdentityPrincipalPort;
import com.traceability.core.infrastructure.authorization.TestIdentityPrincipalPortConfig;
import com.traceability.core.infrastructure.persistence.mongo.TraceabilityEventDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Ficha R-6, Enmienda 1, E2 (G-9; adenda G-9 del plan B, tests G9-1 y G9-2, P-G9-1 (b)), en Mongo real:
 * {@code lastEventAt} del listado de activos de la organización es el {@code occurredAt} del último evento <b>por
 * {@code sequence}</b>, y sale de los eventos que la rehidratación ya carga, sin ninguna consulta más. Los eventos se
 * escriben con {@link EventStorePort#append} para fijar su {@code occurredAt}.
 */
@SpringBootTest(properties = {
        "core.projection.retry.delay=99999999",
        "core.projection.retry.timeout-minutes=1",
        "saga.outbox.delay=99999999"
})
@Testcontainers
@Import(TestIdentityPrincipalPortConfig.class)
class PhysicalAssetListLastEventAtIntegrationTest {

    private static final String ORG = "ORG-G9";
    private static final String EMPLOYEE = "acc-g9-employee";
    private static final SystemActor SYSTEM = new SystemActor("g9-tests");

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:6.0")).withCommand("--replSet", "rs0");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }

    @org.springframework.context.annotation.Configuration
    @SpringBootApplication(scanBasePackages = "com.traceability.core")
    @org.springframework.data.mongodb.repository.config.EnableMongoRepositories(basePackages = "com.traceability.core")
    static class TestConfig {
        @org.springframework.context.annotation.Bean
        java.time.Clock clock() {
            return java.time.Clock.fixed(Instant.parse("2026-10-25T12:00:00Z"), java.time.ZoneOffset.UTC);
        }
    }

    @SpyBean private TestIdentityPrincipalPort identityPrincipalPort;
    @SpyBean private EventStorePort eventStore;
    @MockBean private HashPort hashPort;

    @Autowired private PhysicalAssetOperationalReadPort reads;
    @Autowired private MongoTemplate mongoTemplate;

    @BeforeEach
    void setUp() {
        mongoTemplate.remove(new org.springframework.data.mongodb.core.query.Query(), TraceabilityEventDocument.class);
        identityPrincipalPort.reset();
        identityPrincipalPort.addPrincipal(EMPLOYEE, ORG, Set.of(AuthorizationRole.EMPLOYEE));
    }

    private static String newAsset() {
        return "asset-g9-" + UUID.randomUUID();
    }

    private void append(String assetId, long expectedVersion, PhysicalAssetEventType type, DomainEventPayload payload,
                        Instant at) {
        eventStore.append(assetId, "PhysicalAsset", expectedVersion, List.of(new DomainEvent(type, payload, at)), SYSTEM);
    }

    private void register(String assetId, Instant at) {
        append(assetId, 0, PhysicalAssetEventType.ASSET_REGISTERED, new AssetRegisteredV3Payload(assetId, "FOOD",
                new BigDecimal("10"), "KGS", "WH-1", "CUST-1", null, null, null, null, ORG, "anon:d", null, null), at);
    }

    private Map<String, PhysicalAssetOperationalView> listing() {
        return reads.listForOrganization(ORG, new HumanActor(EMPLOYEE)).stream()
                .collect(Collectors.toMap(PhysicalAssetOperationalView::assetRef, Function.identity()));
    }

    // ── G9-1 ──

    @Test
    void g9_lastEventAt_isTheOccurredAtOfTheHighestSequence_evenIfAnEarlierEventHasALaterOccurredAt() {
        String asset = newAsset();
        Instant registered = Instant.parse("2026-10-01T10:00:00Z");
        Instant dispatched = Instant.parse("2026-10-20T10:00:00Z"); // el más reciente en el tiempo, sequence 2
        Instant received = Instant.parse("2026-10-05T10:00:00Z");   // anterior en el tiempo, último por sequence (3)
        register(asset, registered);
        append(asset, 1, PhysicalAssetEventType.ASSET_DISPATCHED, new AssetDispatchedPayload("CARRIER-1", "WH-1"),
                dispatched);
        append(asset, 2, PhysicalAssetEventType.ASSET_RECEIVED, new AssetReceivedPayload("WH-2", "REC-1"), received);

        PhysicalAssetOperationalView view = listing().get(asset);

        assertThat(view.lifecycleStatus()).isEqualTo("RECEIVED");
        assertThat(view.lastEventAt()).isEqualTo(received);
    }

    @Test
    void g9_aSingleEventAsset_hasTheOccurredAtOfItsGenesis() {
        String asset = newAsset();
        Instant registered = Instant.parse("2026-10-02T08:30:00Z");
        register(asset, registered);

        assertThat(listing().get(asset).lastEventAt()).isEqualTo(registered);
    }

    @Test
    void g9_theIndividualView_doesNotCarryLastEventAt() {
        String asset = newAsset();
        register(asset, Instant.parse("2026-10-02T08:30:00Z"));

        // P-G9-2: el campo es solo del listado (E2)
        assertThat(reads.findOperationalView(asset, new HumanActor(EMPLOYEE)).lastEventAt()).isNull();
    }

    // ── G9-2 ──

    @Test
    void g9_aPageOfSeveralAssets_makesNoReadBeyondOneStreamLoadPerAsset() {
        List<String> assets = List.of(newAsset(), newAsset(), newAsset());
        for (String asset : assets) {
            register(asset, Instant.parse("2026-10-03T09:00:00Z"));
            append(asset, 1, PhysicalAssetEventType.ASSET_DISPATCHED, new AssetDispatchedPayload("C", "WH-1"),
                    Instant.parse("2026-10-04T09:00:00Z"));
        }
        Mockito.clearInvocations(eventStore);

        Map<String, PhysicalAssetOperationalView> listed = listing();

        assertThat(listed.keySet()).containsExactlyInAnyOrderElementsOf(assets);
        assertThat(listed.values()).allSatisfy(v ->
                assertThat(v.lastEventAt()).isEqualTo(Instant.parse("2026-10-04T09:00:00Z")));
        // P-G9-1 (b): las mismas lecturas que antes de G-9, una rehidratación por activo y ninguna más. Se cuenta por
        // activo y solo loadStream: la proyección de core lee en segundo plano por otro método del mismo adaptador
        // (findGenesisPayload), y eso no es del listado
        for (String asset : assets) {
            verify(eventStore, times(1)).loadStream(asset);
        }
        verify(eventStore, times(assets.size())).loadStream(anyString());
    }
}
