package com.traceability.core.application.saga;

import com.traceability.contracts.HashPort;
import com.traceability.contracts.authorization.AuthorizationRole;
import com.traceability.core.application.command.FundCommandService;
import com.traceability.core.application.command.PhysicalAssetCommandService;
import com.traceability.core.application.port.out.EventStorePort;
import com.traceability.core.application.port.out.OutboxPort;
import com.traceability.core.application.port.out.SplitResolutionReadPort;
import com.traceability.core.application.service.TransactionalEventPublisher;
import com.traceability.core.domain.event.DomainEvent;
import com.traceability.core.domain.event.DomainEventPayload;
import com.traceability.core.domain.event.HumanActor;
import com.traceability.core.domain.event.SystemActor;
import com.traceability.core.domain.fund.OrganizationRef;
import com.traceability.core.domain.physicalasset.AssetLifecycleStatus;
import com.traceability.core.domain.physicalasset.PhysicalAsset;
import com.traceability.core.domain.physicalasset.SplitChildIds;
import com.traceability.core.domain.physicalasset.payloads.AssetRegisteredV3Payload;
import com.traceability.core.domain.physicalasset.payloads.AssetSplitCompensatedPayload;
import com.traceability.core.domain.physicalasset.payloads.AssetSplitV3Payload;
import com.traceability.core.infrastructure.authorization.TestIdentityPrincipalPort;
import com.traceability.core.infrastructure.authorization.TestIdentityPrincipalPortConfig;
import com.traceability.core.infrastructure.persistence.mongo.TraceabilityEventDocument;
import com.traceability.core.support.MutableClock;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * B1-bis (plan-b1bis-saga-division.md §4; ADR-007/008 Enmienda 1): saga de la división contra Mongo real, <strong>con
 * {@link MongoTransactionManager}</strong>. Sin él, {@code @Transactional} no tiene efecto en los tests de {@code core}
 * y la barrera se probaría sin transacción.
 */
@SpringBootTest(properties = {
        "core.projection.retry.delay=99999999",
        "core.projection.retry.timeout-minutes=1",
        "saga.outbox.delay=99999999"
})
@Testcontainers
@Import({TestIdentityPrincipalPortConfig.class, SplitSagaIntegrationTest.TestBeans.class})
class SplitSagaIntegrationTest {

    private static final String ORG = "ORG-SPLIT";
    private static final String EMPLOYEE = "acc-split-employee";
    private static final SystemActor SYSTEM = new SystemActor("b1bis-tests");
    private static final Instant T0 = Instant.parse("2026-10-07T00:00:00Z");

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:6.0")).withCommand("--replSet", "rs0");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }

    @Configuration
    @SpringBootApplication(scanBasePackages = "com.traceability.core")
    @org.springframework.data.mongodb.repository.config.EnableMongoRepositories(basePackages = "com.traceability.core")
    static class TestConfig {}

    @TestConfiguration
    static class TestBeans {
        @Bean
        MongoTransactionManager transactionManager(MongoDatabaseFactory dbFactory) {
            return new MongoTransactionManager(dbFactory);
        }

        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(T0);
        }
    }

    @SpyBean private TestIdentityPrincipalPort identityPrincipalPort;
    @MockBean private HashPort hashPort;
    @SpyBean private OutboxPort outboxPort;
    @SpyBean private PhysicalAssetCommandService assets;
    @SpyBean private AssetRegisteredSagaPolicy registrationPolicy;

    @Autowired private FundCommandService funds;
    @Autowired private EventStorePort eventStore;
    @Autowired private TransactionalEventPublisher publisher;
    @Autowired private OutboxSagaCoordinator coordinator;
    @Autowired private SplitPhysicalAssetSagaPolicy splitPolicy;
    @Autowired private SplitResolutionReadPort splitStatus;
    @Autowired private SagaOutboxAdministration administration;
    @Autowired private MongoTemplate mongoTemplate;
    @Autowired private MutableClock clock;

    @BeforeEach
    void setup() {
        dropAll();
        clock.set(T0);
        identityPrincipalPort.reset();
        identityPrincipalPort.addPrincipal(EMPLOYEE, ORG, Set.of(AuthorizationRole.EMPLOYEE));
        when(hashPort.canonicalizeAndHash(any(), any())).thenAnswer(inv -> UUID.randomUUID().toString());
    }

    @AfterEach
    void clean() {
        Mockito.reset(outboxPort, assets, registrationPolicy);
        dropAll();
    }

    private void dropAll() {
        mongoTemplate.dropCollection(TraceabilityEventDocument.class);
        for (String c : List.of("processed_commands", "outbox", "saga_manual_actions")) {
            mongoTemplate.dropCollection(c);
        }
    }

    // ------------------------------------------------------------------ 1, 2

    @Test
    void theSplitWritesTheEventAndTheSagaMessageTogether() {
        String parent = registerParent("CAMP-1", "10");

        String child = assets.splitPhysicalAsset("cmd-1", parent, new BigDecimal("3"), new HumanActor(EMPLOYEE));

        assertThat(child).isEqualTo(SplitChildIds.of(parent, "cmd-1"));
        assertThat(splitsOf(parent)).extracting(AssetSplitV3Payload::childAssetId).containsExactly(child);
        OutboxMessage message = outboxPort.findBySagaTypeAndCorrelationId(SplitPhysicalAssetSagaPolicy.SAGA_TYPE, child).orElseThrow();
        assertThat(message.sourceAggregateId()).isEqualTo(parent);
        assertThat(message.payload()).contains(parent).contains(child);
        assertThat(message.createdAt()).isEqualTo(T0);
        assertThat(message.status()).isEqualTo(OutboxStatus.PENDING);
    }

    // 1
    @Test
    void ifWritingTheSagaMessageFails_theSplitLeavesNothing() {
        String parent = registerParent("CAMP-1", "10");
        doThrow(new IllegalStateException("outbox down"))
                .when(outboxPort).save(argThat(m -> SplitPhysicalAssetSagaPolicy.SAGA_TYPE.equals(m.sagaType())));

        assertThatThrownBy(() -> assets.splitPhysicalAsset("cmd-fail", parent, new BigDecimal("3"), new HumanActor(EMPLOYEE)))
                .hasMessage("outbox down");

        assertThat(splitsOf(parent)).isEmpty();
        assertThat(claimExists("cmd-fail")).isFalse();
        assertThat(mongoTemplate.count(new Query(), "outbox")).as("solo el mensaje de la saga de registro").isEqualTo(1);
    }

    // 2
    @Test
    void theSameCommand_returnsTheSameChild_withoutASecondSplit_andAnotherParentGetsAnotherChild() {
        String parent = registerParent("CAMP-1", "10");
        String other = registerParent("CAMP-1", "10");

        String first = assets.splitPhysicalAsset("cmd-same", parent, new BigDecimal("3"), new HumanActor(EMPLOYEE));
        String again = assets.splitPhysicalAsset("cmd-same", parent, new BigDecimal("3"), new HumanActor(EMPLOYEE));
        String elsewhere = assets.splitPhysicalAsset("cmd-same-other", other, new BigDecimal("3"), new HumanActor(EMPLOYEE));

        assertThat(again).isEqualTo(first);
        assertThat(splitsOf(parent)).hasSize(1);
        assertThat(elsewhere).isNotEqualTo(first);
    }

    // ------------------------------------------------------------------ 3, 4, 5

    @Test
    void theSagaCreatesTheChildWithTheFullInheritance_andTheParentIsReduced() {
        String parent = registerParent("CAMP-7", "10");
        String child = split(parent, "4");

        assertThat(assets.createSplitChild(parent, child)).isEqualTo(SplitResolution.CHILD_CREATED);

        AssetRegisteredV3Payload genesis = genesis(child);
        assertThat(genesis.quantity()).isEqualByComparingTo("4");
        assertThat(genesis.parentAssetRef()).isEqualTo(parent);
        assertThat(genesis.rootAssetRef()).isEqualTo(parent);
        assertThat(genesis.organizationRef()).isEqualTo(ORG);
        assertThat(genesis.campaignRef()).as("criterio 15").isEqualTo("CAMP-7");
        assertThat(genesis.sourceAllocationId()).isEqualTo(genesis(parent).allocationId());
        assertThat(genesis.allocationId()).isNull();
        assertThat(rehydrate(parent).getQuantity()).as("criterio 16").isEqualByComparingTo("6");
        assertThat(claimOutcome(child)).isEqualTo("CHILD_CREATED");
    }

    // 5
    @Test
    void runningTheCreationAgain_orConcurrently_createsASingleChild() throws Exception {
        String parent = registerParent("CAMP-1", "10");
        String child = split(parent, "2");

        List<Future<SplitResolution>> results = race(() -> assets.createSplitChild(parent, child),
                () -> assets.createSplitChild(parent, child));
        settle(results);
        assertThat(assets.createSplitChild(parent, child)).isEqualTo(SplitResolution.CHILD_CREATED);

        assertThat(eventStore.loadStream(child)).hasSize(1);
    }

    // ------------------------------------------------------------------ 6, 8

    @Test
    void compensatingWithoutAChild_reintegratesTheQuantity_andRevivesADepletedParent() {
        String parent = registerParent("CAMP-1", "5");
        String child = split(parent, "5");
        assertThat(rehydrate(parent).getLifecycleStatus()).isEqualTo(AssetLifecycleStatus.DEPLETED);

        assertThat(assets.compensateSplitChild(parent, child)).isEqualTo(SplitResolution.COMPENSATED);
        assertThat(assets.compensateSplitChild(parent, child)).isEqualTo(SplitResolution.COMPENSATED);

        PhysicalAsset p = rehydrate(parent);
        assertThat(p.getQuantity()).isEqualByComparingTo("5");
        assertThat(p.getLifecycleStatus()).isEqualTo(AssetLifecycleStatus.REGISTERED);
        assertThat(compensationsOf(parent)).hasSize(1);
        assertThat(eventStore.loadStream(child)).isEmpty();
    }

    // 8
    @Test
    void eachBranchRespectsTheOther() {
        String parent = registerParent("CAMP-1", "10");
        String created = split(parent, "1");
        String compensated = split(parent, "1");

        assets.createSplitChild(parent, created);
        assertThat(assets.compensateSplitChild(parent, created)).isEqualTo(SplitResolution.CHILD_CREATED);
        assets.compensateSplitChild(parent, compensated);
        assertThat(assets.createSplitChild(parent, compensated)).isEqualTo(SplitResolution.COMPENSATED);

        assertThat(compensationsOf(parent)).extracting(AssetSplitCompensatedPayload::childAssetId).containsExactly(compensated);
        assertThat(eventStore.loadStream(compensated)).isEmpty();
    }

    // ------------------------------------------------------------------ 7: la barrera

    @Test
    void theBarrier_executeAndCompensateAtTheSameTime_neverBothNeverNeither() throws Exception {
        for (int round = 0; round < 20; round++) {
            String parent = registerParent("CAMP-1", "10");
            String child = split(parent, "3");

            List<Future<SplitResolution>> results = race(() -> assets.createSplitChild(parent, child),
                    () -> assets.compensateSplitChild(parent, child));
            settle(results);
            // el perdedor, si abortó, vuelve a intentarlo como haría el coordinador: no cambia nada
            SplitResolution afterCreate = assets.createSplitChild(parent, child);
            SplitResolution afterCompensate = assets.compensateSplitChild(parent, child);

            boolean childExists = !eventStore.loadStream(child).isEmpty();
            boolean compensatedParent = !compensationsOf(parent).isEmpty();
            assertThat(childExists ^ compensatedParent).as("ronda %d: exactamente un efecto", round).isTrue();
            String expected = childExists ? "CHILD_CREATED" : "COMPENSATED";
            assertThat(claimOutcome(child)).as("ronda %d: el reclamo guarda el ganador", round).isEqualTo(expected);
            assertThat(afterCreate.name()).isEqualTo(expected);
            assertThat(afterCompensate.name()).isEqualTo(expected);
            assertThat(rehydrate(parent).getQuantity()).isEqualByComparingTo(childExists ? "7" : "10");
        }
    }

    // ------------------------------------------------------------------ 11

    @Test
    void theChildTakesLocationAndCustodianFromTheSplit_notFromTheCurrentParent() {
        String parent = registerParent("CAMP-1", "10");
        String child = split(parent, "2");
        onParent(parent, p -> p.dispatch("carrier-after-split"));

        assets.createSplitChild(parent, child);

        assertThat(genesis(child).currentLocation()).isEqualTo("WH-1");
        assertThat(genesis(child).custodianRef()).isEqualTo("CUST-1");
    }

    // ------------------------------------------------------------------ 14 y estados

    @Test
    void theStatusPort_namesEveryOutcome() {
        String parent = registerParent("CAMP-1", "10");
        String a = split(parent, "1");
        String b = split(parent, "1");

        assertThat(splitStatus.findStatus(parent, a)).contains(SplitResolutionStatus.PENDING);
        assets.createSplitChild(parent, a);
        assets.compensateSplitChild(parent, b);

        assertThat(splitStatus.findStatus(parent, a)).contains(SplitResolutionStatus.CHILD_CREATED);
        assertThat(splitStatus.findStatus(parent, b)).contains(SplitResolutionStatus.COMPENSATED);
        assertThat(splitStatus.findStatus(parent, "not-a-child")).isEmpty();
        assertThat(splitStatus.findStatus("not-a-parent", a)).isEmpty();
    }

    // ------------------------------------------------------------------ coordinador de punta a punta

    @Test
    void theCoordinatorCreatesTheChild_andCompletesTheMessage() {
        String parent = registerParent("CAMP-1", "10");
        String child = split(parent, "2");

        coordinator.processPendingMessages();

        assertThat(eventStore.loadStream(child)).hasSize(1);
        assertThat(message(child).status()).isEqualTo(OutboxStatus.COMPLETED);
    }

    @Test
    void executeFindingACompensatedSplit_completesTheMessageWithoutCreatingTheChild() {
        String parent = registerParent("CAMP-1", "10");
        String child = split(parent, "2");
        assets.compensateSplitChild(parent, child);

        coordinator.processPendingMessages();

        assertThat(eventStore.loadStream(child)).isEmpty();
        assertThat(message(child).status()).isEqualTo(OutboxStatus.COMPLETED);
    }

    // 20 (Q2: recuperar hacia delante)
    @Test
    void parentDeliveredBeforeTheSagaResolves_theResolutionCreatesTheChildInstead() {
        String parent = registerParent("CAMP-1", "10");
        String child = split(parent, "2");
        doThrow(new IllegalStateException("transient while executing")).when(assets).createSplitChild(parent, child);
        coordinator.processPendingMessages();
        deliverParent(parent);
        doCallRealMethod().when(assets).createSplitChild(parent, child);

        clock.set(T0.plus(Duration.ofHours(4)).plusSeconds(60));
        makeDue(child);
        coordinator.processPendingMessages();

        assertThat(eventStore.loadStream(child)).hasSize(1);
        assertThat(compensationsOf(parent)).isEmpty();
        assertThat(message(child).status()).isEqualTo(OutboxStatus.RESOLVED);
        assertThat(splitStatus.findStatus(parent, child)).contains(SplitResolutionStatus.CHILD_CREATED);
    }

    // 21
    @Test
    void parentDeliveredAndChildImpossible_isUnresolved_untilRetryResolutionFindsTheCauseGone() {
        String parent = registerParent("CAMP-1", "10");
        String child = split(parent, "2");
        deliverParent(parent);
        doThrow(new IllegalArgumentException("child cannot be created")).when(assets).createSplitChild(parent, child);

        clock.set(T0.plus(Duration.ofHours(4)).plusSeconds(60));
        coordinator.processPendingMessages();

        assertThat(message(child).status()).isEqualTo(OutboxStatus.QUARANTINED);
        assertThat(splitStatus.findStatus(parent, child)).contains(SplitResolutionStatus.UNRESOLVED);
        assertThat(administration.getQuarantinedCount()).isEqualTo(1);
        assertThat(administration.listQuarantined(SplitPhysicalAssetSagaPolicy.SAGA_TYPE, 10)).anyMatch(l -> l.contains(child));

        doCallRealMethod().when(assets).createSplitChild(parent, child);
        administration.retryResolution(message(child).messageId(), "operator-1", "fixed the cause");
        coordinator.processPendingMessages();

        assertThat(eventStore.loadStream(child)).hasSize(1);
        assertThat(message(child).status()).isEqualTo(OutboxStatus.RESOLVED);
        assertThat(splitStatus.findStatus(parent, child)).contains(SplitResolutionStatus.CHILD_CREATED);
        assertThat(manualActions(message(child).messageId())).hasSize(1);
    }

    // ------------------------------------------------------------------ 19, 22: salida manual

    @Test
    void manualResolutionOfASplit_isNamed_conditional_andAudited() {
        String parent = registerParent("CAMP-1", "10");
        String child = split(parent, "2");
        quarantine(child);
        String messageId = message(child).messageId();

        assertThatThrownBy(() -> administration.markResolvedManually(messageId, " ", "note"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> administration.markResolvedManually(messageId, "operator-1", ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(manualActions(messageId)).isEmpty();

        clock.set(T0.plus(Duration.ofHours(9)));
        administration.markResolvedManually(messageId, "operator-1", "child registered by hand as asset X");

        assertThat(message(child).status()).isEqualTo(OutboxStatus.RESOLVED);
        assertThat(message(child).manualResolvedBy()).isEqualTo("operator-1");
        assertThat(splitStatus.findStatus(parent, child)).contains(SplitResolutionStatus.RESOLVED_MANUALLY);
        Document audit = manualActions(messageId).get(0);
        assertThat(audit.getString("operator")).isEqualTo("operator-1");
        assertThat(audit.getString("note")).isEqualTo("child registered by hand as asset X");
        assertThat(audit.getString("action")).isEqualTo("MARK_RESOLVED_MANUALLY");
        assertThat(audit.getString("previousStatus")).isEqualTo("QUARANTINED");
        assertThat(audit.getDate("at").toInstant()).isEqualTo(T0.plus(Duration.ofHours(9)));

        // después, ninguna rama tiene efecto
        assertThat(assets.createSplitChild(parent, child)).isEqualTo(SplitResolution.RESOLVED_MANUALLY);
        assertThat(assets.compensateSplitChild(parent, child)).isEqualTo(SplitResolution.RESOLVED_MANUALLY);
        assertThat(eventStore.loadStream(child)).isEmpty();
        assertThat(compensationsOf(parent)).isEmpty();

        // condicional: ya no está en cuarentena
        assertThatThrownBy(() -> administration.markResolvedManually(messageId, "operator-2", "again"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> administration.retryResolution(messageId, "operator-2", "again"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(manualActions(messageId)).hasSize(1);
    }

    @Test
    void manualResolution_failsWithoutChanges_ifTheSplitWasAlreadyResolved() {
        String parent = registerParent("CAMP-1", "10");
        String child = split(parent, "2");
        quarantine(child);
        assets.compensateSplitChild(parent, child);
        String messageId = message(child).messageId();

        assertThatThrownBy(() -> administration.markResolvedManually(messageId, "operator-1", "too late"))
                .isInstanceOf(IllegalStateException.class);

        assertThat(message(child).status()).isEqualTo(OutboxStatus.QUARANTINED);
        assertThat(manualActions(messageId)).isEmpty();
        assertThat(splitStatus.findStatus(parent, child)).contains(SplitResolutionStatus.COMPENSATED);
    }

    // ------------------------------------------------------------------ 18: efecto de las 4 h sobre la saga de registro

    @Test
    void registrationSaga_withTheDefaultWindow_retriesUntilFourHours_thenReverses_andRetriesAFailedReversal() {
        assertThat(coordinator.executionWindow()).as("valor por defecto, sin propiedad").isEqualTo(Duration.ofHours(4));
        String fundId = UUID.randomUUID().toString();
        String allocationId = UUID.randomUUID().toString();
        funds.clearFundsGenesis(UUID.randomUUID().toString(), fundId, new OrganizationRef(ORG), null, "DONOR-1", "COP",
                1000L, "SRC", SYSTEM);
        funds.requestAllocation(UUID.randomUUID().toString(), fundId, allocationId, 100L, SYSTEM);
        assets.registerPhysicalAsset(UUID.randomUUID().toString(), fundId, ORG, "FOOD", BigDecimal.ONE, "KGS", "CUST-1",
                "WH-1", allocationId, null, SYSTEM);
        doThrow(new IllegalStateException("confirm keeps failing")).when(registrationPolicy).execute(any());

        clock.set(T0.plus(Duration.ofHours(3)).plus(Duration.ofMinutes(59)));
        coordinator.processPendingMessages();
        Mockito.verify(registrationPolicy, Mockito.times(1)).execute(any());
        Mockito.verify(registrationPolicy, Mockito.never()).compensate(any());

        doThrow(new IllegalStateException("reverse fails once")).doCallRealMethod().when(registrationPolicy).compensate(any());
        clock.set(T0.plus(Duration.ofHours(4)).plusSeconds(60));
        makeDueAll();
        coordinator.processPendingMessages();
        OutboxMessage registration = registrationMessage();
        assertThat(registration.status()).as("antes de la enmienda: QUARANTINED sin compensar").isEqualTo(OutboxStatus.PENDING);
        assertThat(registration.inResolution()).isTrue();

        clock.set(registration.nextRetryAt());
        coordinator.processPendingMessages();

        assertThat(registrationMessage().status()).isEqualTo(OutboxStatus.RESOLVED);
        assertThat(eventStore.loadStream(fundId)).extracting(e -> e.eventType().name()).contains("ALLOCATION_REVERSED");
    }

    // ------------------------------------------------------------------ utilidades

    private String registerParent(String fundCampaignRef, String quantity) {
        String fundId = UUID.randomUUID().toString();
        String allocationId = UUID.randomUUID().toString();
        funds.clearFundsGenesis(UUID.randomUUID().toString(), fundId, new OrganizationRef(ORG), fundCampaignRef,
                "DONOR-1", "COP", 1000L, "SRC-1", SYSTEM);
        assets.registerPhysicalAsset(UUID.randomUUID().toString(), fundId, ORG, "FOOD", new BigDecimal(quantity), "KGS",
                "CUST-1", "WH-1", allocationId, null, SYSTEM);
        return mongoTemplate.findOne(new Query(Criteria.where("eventType").is("ASSET_REGISTERED")
                .and("payload.allocationId").is(allocationId)), TraceabilityEventDocument.class).getStreamId();
    }

    private String split(String parent, String quantity) {
        return assets.splitPhysicalAsset(UUID.randomUUID().toString(), parent, new BigDecimal(quantity), new HumanActor(EMPLOYEE));
    }

    private PhysicalAsset rehydrate(String assetId) {
        List<DomainEvent> events = eventStore.loadStream(assetId);
        return PhysicalAsset.rehydrate(assetId, events.stream().map(DomainEvent::payload).toList(), events.size());
    }

    private void onParent(String assetId, Consumer<PhysicalAsset> action) {
        PhysicalAsset asset = rehydrate(assetId);
        long version = asset.getVersion();
        action.accept(asset);
        publisher.appendAndOutbox(assetId, "PhysicalAsset", version, asset.getUncommittedEvents(), SYSTEM, null, null);
    }

    private void deliverParent(String parent) {
        onParent(parent, p -> p.dispatch("carrier-1"));
        assets.deliverAsset(UUID.randomUUID().toString(), parent, "FINAL-CUST", "BENEFICIARY", "LOC-FINAL", "EVIDENCE",
                T0, SYSTEM);
        assertThat(rehydrate(parent).getLifecycleStatus()).isEqualTo(AssetLifecycleStatus.DELIVERED);
    }

    private List<AssetSplitV3Payload> splitsOf(String parent) {
        return payloads(parent).stream().filter(AssetSplitV3Payload.class::isInstance).map(AssetSplitV3Payload.class::cast).toList();
    }

    private List<AssetSplitCompensatedPayload> compensationsOf(String parent) {
        return payloads(parent).stream().filter(AssetSplitCompensatedPayload.class::isInstance)
                .map(AssetSplitCompensatedPayload.class::cast).toList();
    }

    private List<DomainEventPayload> payloads(String streamId) {
        return eventStore.loadStream(streamId).stream().map(DomainEvent::payload).toList();
    }

    private AssetRegisteredV3Payload genesis(String assetId) {
        return (AssetRegisteredV3Payload) payloads(assetId).get(0);
    }

    private boolean claimExists(String commandId) {
        return mongoTemplate.count(new Query(Criteria.where("_id").is(commandId)), "processed_commands") > 0;
    }

    private String claimOutcome(String childAssetId) {
        Document claim = mongoTemplate.findOne(new Query(Criteria.where("_id").is(SplitResolution.claimKey(childAssetId))),
                Document.class, "processed_commands");
        return claim == null ? null : claim.getString("outcome");
    }

    private OutboxMessage message(String childAssetId) {
        return outboxPort.findBySagaTypeAndCorrelationId(SplitPhysicalAssetSagaPolicy.SAGA_TYPE, childAssetId).orElseThrow();
    }

    private OutboxMessage registrationMessage() {
        return mongoTemplate.find(new Query(Criteria.where("sagaType").is("ASSET_REGISTRATION_SAGA")), Document.class, "outbox")
                .stream().findFirst().map(d -> outboxPort.findById(d.getString("_id")).orElseThrow()).orElseThrow();
    }

    private void quarantine(String childAssetId) {
        OutboxMessage m = message(childAssetId);
        outboxPort.update(m.withState(OutboxStatus.QUARANTINED, m.retryCount(), m.nextRetryAt(), T0, "test"));
    }

    private void makeDue(String childAssetId) {
        OutboxMessage m = message(childAssetId);
        outboxPort.update(m.withState(m.status(), m.retryCount(), Instant.EPOCH, m.resolutionStartedAt(), m.lastFailureReason()));
    }

    private void makeDueAll() {
        mongoTemplate.updateMulti(new Query(Criteria.where("status").is("PENDING")),
                new org.springframework.data.mongodb.core.query.Update().set("nextRetryAt", Instant.EPOCH), "outbox");
    }

    private List<Document> manualActions(String messageId) {
        return mongoTemplate.find(new Query(Criteria.where("messageId").is(messageId)), Document.class, "saga_manual_actions");
    }

    @SafeVarargs
    private static <T> List<Future<T>> race(java.util.concurrent.Callable<T>... actions) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(actions.length);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new java.util.ArrayList<>();
            for (java.util.concurrent.Callable<T> action : actions) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return action.call();
                }));
            }
            start.countDown();
            return futures;
        } finally {
            pool.shutdown();
        }
    }

    private static <T> void settle(List<Future<T>> futures) {
        for (Future<T> f : futures) {
            try {
                f.get();
            } catch (Exception ignored) {
                // el perdedor puede abortar por conflicto de escritura; el test comprueba el estado final
            }
        }
    }
}
