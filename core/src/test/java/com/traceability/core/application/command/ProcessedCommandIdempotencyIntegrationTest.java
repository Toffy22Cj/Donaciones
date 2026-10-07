package com.traceability.core.application.command;

import com.traceability.contracts.HashPort;
import com.traceability.core.application.port.out.EventStorePort;
import com.traceability.core.application.service.TransactionalEventPublisher;
import com.traceability.core.domain.event.DomainEvent;
import com.traceability.core.domain.event.SystemActor;
import com.traceability.core.domain.fund.Fund;
import com.traceability.core.domain.fund.OrganizationRef;
import com.traceability.core.domain.physicalasset.PhysicalAsset;
import com.traceability.core.domain.physicalasset.exceptions.InvalidAssetTransitionException;
import com.traceability.core.domain.physicalasset.payloads.AssetDeliveredPayload;
import com.traceability.core.infrastructure.persistence.mongo.ProcessedCommandDocument;
import com.traceability.core.infrastructure.persistence.mongo.TraceabilityEventDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.mock.mockito.MockBean;
import com.traceability.contracts.authorization.IdentityPrincipalPort;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "core.projection.retry.delay=100",
        "core.projection.retry.timeout-minutes=5"
})
@Testcontainers
@org.springframework.context.annotation.Import(com.traceability.core.support.TransactionProbe.Config.class)
class ProcessedCommandIdempotencyIntegrationTest {

    @MockBean
    private IdentityPrincipalPort identityPrincipalPort;



    @MockBean
    private HashPort hashPort;

    // We do NOT mock OutboxPort because it's not needed for this test's DoD.
    // The real MongoOutboxPort will be injected.

    @Container
    static MongoDBContainer mongoDBContainer = new MongoDBContainer(DockerImageName.parse("mongo:6.0"))
            .withCommand("--replSet", "rs0");

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongoDBContainer::getReplicaSetUrl);
    }

    @Configuration
    @SpringBootApplication(scanBasePackages = "com.traceability.core")
    @org.springframework.data.mongodb.repository.config.EnableMongoRepositories(basePackages = "com.traceability.core")
    @org.springframework.scheduling.annotation.EnableScheduling
    static class TestConfig {
        @Bean
        MongoTransactionManager transactionManager(MongoDatabaseFactory dbFactory) {
            return new MongoTransactionManager(dbFactory);
        }
    }

    @Autowired
    private FundCommandService fundCommandService;

    @Autowired
    private PhysicalAssetCommandService physicalAssetCommandService;

    @Autowired
    private EventStorePort eventStorePort;

    @Autowired
    private TransactionalEventPublisher transactionalEventPublisher;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private com.traceability.core.support.TransactionProbe transactionProbe;

    @BeforeEach
    void setup() {
        mongoTemplate.dropCollection(TraceabilityEventDocument.class);
        mongoTemplate.dropCollection(ProcessedCommandDocument.class);
        // Explicitly create idx_stream_sequence in Event Store to trigger concurrency conflict correctly
        mongoTemplate.getCollection("events").createIndex(
                new org.bson.Document("streamId", 1).append("sequence", 1),
                new com.mongodb.client.model.IndexOptions().unique(true)
        );
    }

    @AfterEach
    void clean() {
        mongoTemplate.dropCollection(TraceabilityEventDocument.class);
        mongoTemplate.dropCollection(ProcessedCommandDocument.class);
    }

    private String setupFund() {
        String fundId = UUID.randomUUID().toString();
        fundCommandService.clearFundsGenesis(
                UUID.randomUUID().toString(),
                fundId,
                new OrganizationRef("ORG-1"),
                "CAMP-1",
                "DONOR-1",
                "COP",
                1000L,
                "REF",
                new SystemActor("test")
        );

        Fund fund = Fund.rehydrate(fundId, eventStorePort.loadStream(fundId).stream().map(DomainEvent::payload).toList(), 1);
        fund.requestAllocation("ALLOC-1", 500L);
        transactionalEventPublisher.appendAndOutbox(fundId, "Fund", 1, fund.getUncommittedEvents(), new SystemActor("test"), null, UUID.randomUUID().toString());
        return fundId;
    }

    @Test
    void confirmAllocation_concurrency() throws InterruptedException {
        String fundId = setupFund();
        String commandId = UUID.randomUUID().toString();

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger errorCount = new AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    latch.await();
                    fundCommandService.confirmAllocation(commandId, fundId, "ALLOC-1", new SystemActor("test"));
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                    System.err.println("Exception in Thread: " + e.getMessage());
                    e.printStackTrace(System.err);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        latch.countDown();
        doneLatch.await(10, TimeUnit.SECONDS);

        assertEquals(2, successCount.get(), "Both invocations should return without error");
        assertEquals(0, errorCount.get(), "Zero unhandled exceptions");

        List<DomainEvent> stream = eventStorePort.loadStream(fundId);
        // 1 register + 1 request + 1 confirm = 3 events total.
        assertEquals(3, stream.size(), "Exactly three events in the Event Store for confirmAllocation");
        assertEquals("ALLOCATION_CONFIRMED", stream.get(2).eventType().name());

        // We check processed commands
        List<ProcessedCommandDocument> docs = mongoTemplate.findAll(ProcessedCommandDocument.class);
        // setupFund creates 2 ProcessedCommandDocuments (register, request)
        // confirmAllocation adds 1.
        // Total should be 3.
        assertEquals(3, docs.size(), "Exactly three messages in ProcessedCommand");

        long count = docs.stream().filter(d -> d.getCommandId().equals(commandId)).count();
        assertEquals(1, count, "Exactly one ProcessedCommand for the specific commandId");
    }

    @Test
    void reverseAllocation_concurrency() throws InterruptedException {
        String fundId = setupFund();
        String commandId = UUID.randomUUID().toString();

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger errorCount = new AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    latch.await();
                    fundCommandService.reverseAllocation(commandId, fundId, "ALLOC-1", "Reason", new SystemActor("test"));
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        latch.countDown();
        doneLatch.await(10, TimeUnit.SECONDS);

        assertEquals(2, successCount.get(), "Both invocations should return without error");
        assertEquals(0, errorCount.get(), "Zero unhandled exceptions");

        List<DomainEvent> stream = eventStorePort.loadStream(fundId);
        assertEquals(3, stream.size(), "Exactly three events in the Event Store for reverseAllocation");
        assertEquals("ALLOCATION_REVERSED", stream.get(2).eventType().name());

        List<ProcessedCommandDocument> docs = mongoTemplate.findAll(ProcessedCommandDocument.class);
        assertEquals(3, docs.size(), "Exactly three messages in ProcessedCommand");

        long count = docs.stream().filter(d -> d.getCommandId().equals(commandId)).count();
        assertEquals(1, count, "Exactly one ProcessedCommand for the specific commandId");
    }

    @Test
    void rollback_on_conflict() {
        String fundId = setupFund();
        transactionProbe.reset();
        String commandId = UUID.randomUUID().toString();

        List<DomainEvent> stream = eventStorePort.loadStream(fundId);
        assertFalse(stream.isEmpty());

        Fund f = Fund.rehydrate(fundId, stream.stream().map(DomainEvent::payload).toList(), stream.size());
        f.confirmAllocation("ALLOC-1");
        List<DomainEvent> newEvents = f.getUncommittedEvents();

        // Simular un conflicto de versión pasando un expectedVersion equivocado (9999L en vez de 3L)
        // Esto causará que eventStorePort.append lanze DataIntegrityViolationException (o RuntimeException)
        // Y hará que la transacción actual lance rollback, revirtiendo el tryClaim inicial.
        assertThrows(Exception.class, () -> {
            transactionalEventPublisher.appendAndOutbox(fundId, "Fund", 9999L, newEvents, new SystemActor("test"), Collections.emptyList(), commandId);
        });

        transactionProbe.assertEveryWriteWasTransactional();
        // The document should not exist because of the rollback
        List<ProcessedCommandDocument> docs = mongoTemplate.findAll(ProcessedCommandDocument.class);
        long count = docs.stream().filter(d -> d.getCommandId().equals(commandId)).count();
        assertEquals(0, count, "The processed command should NOT persist after rollback");
    }

    @Test
    void confirmAllocation_happyPath() {
        String fundId = setupFund();
        String commandId = UUID.randomUUID().toString();

        fundCommandService.confirmAllocation(commandId, fundId, "ALLOC-1", new SystemActor("test"));

        List<DomainEvent> stream = eventStorePort.loadStream(fundId);
        assertEquals(3, stream.size(), "Exactly three events in the Event Store for confirmAllocation");
        assertEquals("ALLOCATION_CONFIRMED", stream.get(2).eventType().name());

        List<ProcessedCommandDocument> docs = mongoTemplate.findAll(ProcessedCommandDocument.class);
        long count = docs.stream().filter(d -> d.getCommandId().equals(commandId)).count();
        assertEquals(1, count, "Exactly one ProcessedCommand for the specific commandId");
    }

    @Test
    void confirmAllocation_sameCommandId_retry_scenarioA() {
        String fundId = setupFund();
        String commandId = UUID.randomUUID().toString();

        // First attempt
        fundCommandService.confirmAllocation(commandId, fundId, "ALLOC-1", new SystemActor("test"));

        // Second attempt with SAME commandId
        fundCommandService.confirmAllocation(commandId, fundId, "ALLOC-1", new SystemActor("test"));

        List<DomainEvent> stream = eventStorePort.loadStream(fundId);
        assertEquals(3, stream.size(), "Events should remain 3, no duplicates");

        List<ProcessedCommandDocument> docs = mongoTemplate.findAll(ProcessedCommandDocument.class);
        long count = docs.stream().filter(d -> d.getCommandId().equals(commandId)).count();
        assertEquals(1, count, "Only one claim for this commandId");
    }

    @Test
    void confirmAllocation_differentCommandId_redundant_scenarioB() {
        String fundId = setupFund();
        String commandId1 = UUID.randomUUID().toString();
        String commandId2 = UUID.randomUUID().toString();

        // First command confirms it
        fundCommandService.confirmAllocation(commandId1, fundId, "ALLOC-1", new SystemActor("test"));

        // Second command tries to confirm it again (redundant)
        fundCommandService.confirmAllocation(commandId2, fundId, "ALLOC-1", new SystemActor("test"));

        List<DomainEvent> stream = eventStorePort.loadStream(fundId);
        assertEquals(3, stream.size(), "No new events for redundant action");

        List<ProcessedCommandDocument> docs = mongoTemplate.findAll(ProcessedCommandDocument.class);
        assertTrue(docs.stream().anyMatch(d -> d.getCommandId().equals(commandId1)));
        assertTrue(docs.stream().anyMatch(d -> d.getCommandId().equals(commandId2)));
    }

    @Test
    void confirmAllocation_invalidTransition_scenarioC() {
        String fundId = setupFund();
        String commandId1 = UUID.randomUUID().toString();
        String commandId2 = UUID.randomUUID().toString();

        fundCommandService.confirmAllocation(commandId1, fundId, "ALLOC-1", new SystemActor("test"));

        // Try to reverse it after it's confirmed, which is an invalid transition according to rules
        assertThrows(com.traceability.core.domain.fund.exceptions.InvalidFundTransitionException.class, () -> {
            fundCommandService.reverseAllocation(commandId2, fundId, "ALLOC-1", "Reason", new SystemActor("test"));
        });

        List<ProcessedCommandDocument> docs = mongoTemplate.findAll(ProcessedCommandDocument.class);
        assertFalse(docs.stream().anyMatch(d -> d.getCommandId().equals(commandId2)), "Failed invalid transition should not save commandId");
    }

    // --- deliverAsset (A7.1 on PhysicalAsset) ---

    private static final Instant DELIVERED_AT = Instant.parse("2026-01-01T00:00:00Z");

    private String setupDispatchedAsset() {
        String assetId = UUID.randomUUID().toString();
        PhysicalAsset asset = PhysicalAsset.register(assetId, "FOOD", new java.math.BigDecimal("10"), "KG",
                "WAREHOUSE-A", "CUSTODIAN-1", null, assetId, null, null, "ORG-1", null);
        asset.dispatch("CARRIER-1");
        transactionalEventPublisher.appendAndOutbox(assetId, "PhysicalAsset", 0, asset.getUncommittedEvents(),
                new SystemActor("test"), null, UUID.randomUUID().toString());
        return assetId;
    }

    private void deliver(String commandId, String assetId, String beneficiaryRef, String evidenceRef) {
        physicalAssetCommandService.deliverAsset(commandId, assetId, "CLINIC-1", beneficiaryRef, "CLINIC-LOC",
                evidenceRef, DELIVERED_AT, new SystemActor("test"));
    }

    private boolean claimed(String commandId) {
        return mongoTemplate.findAll(ProcessedCommandDocument.class).stream()
                .anyMatch(d -> d.getCommandId().equals(commandId));
    }

    @Test
    void deliverAsset_sameCommandId_retry_scenarioA() {
        String assetId = setupDispatchedAsset();
        String commandId = UUID.randomUUID().toString();

        deliver(commandId, assetId, "BENEFICIARY-A", "EVIDENCE-1");
        deliver(commandId, assetId, "BENEFICIARY-A", "EVIDENCE-1");

        assertEquals(3, eventStorePort.loadStream(assetId).size(), "REGISTERED, DISPATCHED, DELIVERED — no duplicate");
        assertEquals(1, mongoTemplate.findAll(ProcessedCommandDocument.class).stream()
                .filter(d -> d.getCommandId().equals(commandId)).count());
    }

    @Test
    void deliverAsset_differentCommandId_exactRedundant_scenarioB() {
        String assetId = setupDispatchedAsset();
        String commandId1 = UUID.randomUUID().toString();
        String commandId2 = UUID.randomUUID().toString();

        deliver(commandId1, assetId, "BENEFICIARY-A", "EVIDENCE-1");
        deliver(commandId2, assetId, "BENEFICIARY-A", "EVIDENCE-1");

        assertEquals(3, eventStorePort.loadStream(assetId).size(), "No new event for an exact redundant delivery");
        assertTrue(claimed(commandId1));
        assertTrue(claimed(commandId2), "Exact redundancy reaches appendAndOutbox and records the commandId");
    }

    @Test
    void deliverAsset_differentBeneficiary_rejectedWithoutEffects() {
        String assetId = setupDispatchedAsset();
        String commandId1 = UUID.randomUUID().toString();
        String commandId2 = UUID.randomUUID().toString();

        deliver(commandId1, assetId, "BENEFICIARY-A", "EVIDENCE-1");

        assertThrows(InvalidAssetTransitionException.class,
                () -> deliver(commandId2, assetId, "BENEFICIARY-B", "EVIDENCE-1"));

        List<DomainEvent> stream = eventStorePort.loadStream(assetId);
        assertEquals(3, stream.size());
        assertEquals("BENEFICIARY-A", ((AssetDeliveredPayload) stream.get(2).payload()).beneficiaryRef());
        assertFalse(claimed(commandId2), "A rejected delivery must not be recorded as processed");
    }

    @Test
    void deliverAsset_nullEvidence_exactRedundant_isNoOp() {
        String assetId = setupDispatchedAsset();
        String commandId1 = UUID.randomUUID().toString();
        String commandId2 = UUID.randomUUID().toString();

        deliver(commandId1, assetId, "BENEFICIARY-A", null);
        assertDoesNotThrow(() -> deliver(commandId2, assetId, "BENEFICIARY-A", null));

        assertEquals(3, eventStorePort.loadStream(assetId).size());
        assertTrue(claimed(commandId2));
    }

    @Test
    void deliverAsset_invalidTransition_notDispatched_scenarioC() {
        String assetId = UUID.randomUUID().toString();
        PhysicalAsset asset = PhysicalAsset.register(assetId, "FOOD", new java.math.BigDecimal("10"), "KG",
                "WAREHOUSE-A", "CUSTODIAN-1", null, assetId, null, null, "ORG-1", null);
        transactionalEventPublisher.appendAndOutbox(assetId, "PhysicalAsset", 0, asset.getUncommittedEvents(),
                new SystemActor("test"), null, UUID.randomUUID().toString());
        String commandId = UUID.randomUUID().toString();

        assertThrows(InvalidAssetTransitionException.class,
                () -> deliver(commandId, assetId, "BENEFICIARY-A", "EVIDENCE-1"));

        assertEquals(1, eventStorePort.loadStream(assetId).size());
        assertFalse(claimed(commandId));
    }
}
