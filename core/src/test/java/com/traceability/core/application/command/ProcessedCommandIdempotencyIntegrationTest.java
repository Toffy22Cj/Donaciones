package com.traceability.core.application.command;

import com.traceability.contracts.HashPort;
import com.traceability.core.application.port.out.EventStorePort;
import com.traceability.core.application.service.TransactionalEventPublisher;
import com.traceability.core.domain.event.DomainEvent;
import com.traceability.core.domain.event.SystemActor;
import com.traceability.core.domain.fund.Fund;
import com.traceability.core.domain.fund.OrganizationRef;
import com.traceability.core.infrastructure.persistence.mongo.ProcessedCommandDocument;
import com.traceability.core.infrastructure.persistence.mongo.TraceabilityEventDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
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
class ProcessedCommandIdempotencyIntegrationTest {

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
    private EventStorePort eventStorePort;
    
    @Autowired
    private TransactionalEventPublisher transactionalEventPublisher;

    @Autowired
    private MongoTemplate mongoTemplate;

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
}
