package com.traceability.core.infrastructure.persistence.mongo;

import com.traceability.contracts.HashPort;
import com.traceability.core.application.event.EventCanonicalMapper;
import com.traceability.core.application.exception.ConcurrencyConflictException;
import com.traceability.core.application.exception.SequenceGapException;
import com.traceability.core.application.port.out.EventStorePort;
import com.traceability.core.application.port.out.OutboxPort;
import com.traceability.core.application.saga.OutboxMessage;
import com.traceability.core.application.saga.OutboxStatus;
import com.traceability.core.domain.event.DomainEvent;
import com.traceability.core.domain.event.DomainEventPayload;
import com.traceability.core.domain.physicalasset.AssetLifecycleStatus;
import com.traceability.core.domain.physicalasset.PhysicalAsset;
import com.traceability.core.domain.physicalasset.payloads.AssetDispatchedPayload;
import com.traceability.core.domain.physicalasset.payloads.AssetRegisteredPayload;
import com.traceability.core.domain.physicalasset.payloads.AssetSplitPayload;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
class MongoEventStoreAdapterTest {

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
    static class TestConfig {
        @Bean
        public HashPort hashPort() {
            return new HashPort() {
                @Override
                public String canonicalizeAndHash(Map<String, Object> eventData, String previousHash) {
                    return "dummy-hash-" + eventData.hashCode() + "-" + previousHash;
                }
            };
        }

        @Bean
        public TestRollbackService testRollbackService(EventStorePort esPort, OutboxPort obPort) {
            return new TestRollbackService(esPort, obPort);
        }

        @Bean
        public org.springframework.data.mongodb.MongoTransactionManager transactionManager(org.springframework.data.mongodb.MongoDatabaseFactory dbFactory) {
            return new org.springframework.data.mongodb.MongoTransactionManager(dbFactory);
        }
    }

    static class TestRollbackService {
        private final EventStorePort eventStorePort;
        private final OutboxPort outboxPort;

        public TestRollbackService(EventStorePort esPort, OutboxPort obPort) {
            this.eventStorePort = esPort;
            this.outboxPort = obPort;
        }

        @Transactional
        public void appendAndFail(String streamId, DomainEvent event, OutboxMessage msg) {
            eventStorePort.append(streamId, "PhysicalAsset", 0, event, "actor");
            
            outboxPort.save(msg); // Attempt write to outbox
            
            // EXACTLY HERE: After attempting both writes, but before transaction commit
            throw new RuntimeException("Forced failure after event and outbox writes");
        }
    }

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private MongoEventStoreAdapter eventStoreAdapter;

    @Autowired
    private TestRollbackService testRollbackService;

    @BeforeEach
    void setup() {
        mongoTemplate.getCollection("event_store").createIndex(
                new org.bson.Document("streamId", 1).append("sequence", 1),
                new com.mongodb.client.model.IndexOptions().unique(true)
        );
    }

    @AfterEach
    void clean() {
        mongoTemplate.dropCollection(TraceabilityEventDocument.class);
        mongoTemplate.dropCollection(OutboxMessageDocument.class);
    }

    @Test
    void testAppend_GenesisEvent_UsesGenesisHash() {
        AssetRegisteredPayload payload = new AssetRegisteredPayload("asset-1", "VACCINE", 100, "DOSES", "loc-A", "cust-A", null, null, null, null);
        DomainEvent event = new DomainEvent(() -> "ASSET_REGISTERED", payload, Instant.now());

        eventStoreAdapter.append("stream-1", "PhysicalAsset", 0, event, "actor-1");

        List<TraceabilityEventDocument> docs = mongoTemplate.findAll(TraceabilityEventDocument.class);
        assertEquals(1, docs.size());
        assertEquals(1, docs.get(0).getSequence());
        assertEquals(DomainEvent.GENESIS_HASH, docs.get(0).getPreviousHash());
    }

    @Test
    void testAppend_ChainedEvent_UsesPreviousHash() {
        AssetRegisteredPayload payload1 = new AssetRegisteredPayload("asset-1", "VACCINE", 100, "DOSES", "loc-A", "cust-A", null, null, null, null);
        DomainEvent event1 = new DomainEvent(() -> "ASSET_REGISTERED", payload1, Instant.now());
        eventStoreAdapter.append("stream-2", "PhysicalAsset", 0, event1, "actor-1");

        AssetDispatchedPayload payload2 = new AssetDispatchedPayload("trans-A", "loc-A");
        DomainEvent event2 = new DomainEvent(() -> "ASSET_DISPATCHED", payload2, Instant.now());
        eventStoreAdapter.append("stream-2", "PhysicalAsset", 1, event2, "actor-1");

        List<TraceabilityEventDocument> docs = mongoTemplate.findAll(TraceabilityEventDocument.class);
        assertEquals(2, docs.size());
        
        TraceabilityEventDocument doc1 = docs.stream().filter(d -> d.getSequence() == 1).findFirst().get();
        TraceabilityEventDocument doc2 = docs.stream().filter(d -> d.getSequence() == 2).findFirst().get();
        
        assertEquals(doc1.getEventHash(), doc2.getPreviousHash());
    }

    @Test
    void testAppend_ConcurrencyConflict() throws InterruptedException {
        AssetRegisteredPayload payload1 = new AssetRegisteredPayload("asset-1", "VACCINE", 100, "DOSES", "loc-A", "cust-A", null, null, null, null);
        DomainEvent event1 = new DomainEvent(() -> "ASSET_REGISTERED", payload1, Instant.now());
        eventStoreAdapter.append("stream-3", "PhysicalAsset", 0, event1, "actor-1");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch latch = new CountDownLatch(2);
        
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);
        
        Runnable task = () -> {
            try {
                AssetDispatchedPayload p = new AssetDispatchedPayload("trans", "dest");
                DomainEvent e = new DomainEvent(() -> "ASSET_DISPATCHED", p, Instant.now());
                eventStoreAdapter.append("stream-3", "PhysicalAsset", 1, e, "actor");
                successCount.incrementAndGet();
            } catch (ConcurrencyConflictException ex) {
                conflictCount.incrementAndGet();
            } finally {
                latch.countDown();
            }
        };

        executor.submit(task);
        executor.submit(task);
        latch.await();

        assertEquals(1, successCount.get());
        assertEquals(1, conflictCount.get());
        assertEquals(2, mongoTemplate.findAll(TraceabilityEventDocument.class).size());
    }

    @Test
    void testAppend_SequenceGap() {
        AssetRegisteredPayload payload1 = new AssetRegisteredPayload("asset-1", "VACCINE", 100, "DOSES", "loc-A", "cust-A", null, null, null, null);
        DomainEvent event1 = new DomainEvent(() -> "ASSET_REGISTERED", payload1, Instant.now());
        
        assertThrows(SequenceGapException.class, () -> 
            eventStoreAdapter.append("stream-4", "PhysicalAsset", 5, event1, "actor-1")
        );
    }

    @Test
    void testTransactionalRollback() {
        AssetRegisteredPayload payload = new AssetRegisteredPayload("asset-1", "VACCINE", 100, "DOSES", "loc-A", "cust-A", null, null, null, null);
        DomainEvent event = new DomainEvent(() -> "ASSET_REGISTERED", payload, Instant.now());
        OutboxMessage outboxMsg = new OutboxMessage("msg-1", "SAGA", "stream-rb", "corr-1", "{}", OutboxStatus.PENDING, 0, Instant.now(), Instant.now());

        assertThrows(RuntimeException.class, () -> 
            testRollbackService.appendAndFail("stream-rb", event, outboxMsg)
        );

        assertEquals(0, mongoTemplate.findAll(TraceabilityEventDocument.class).size());
        assertEquals(0, mongoTemplate.findAll(OutboxMessageDocument.class).size());
    }

    @Test
    void testLoadStream_Ordering() {
        TraceabilityEventDocument doc2 = TraceabilityEventDocument.builder()
            .eventId("id2").streamId("stream-ord").aggregateType("T").sequence(2)
            .eventType("ASSET_DISPATCHED").payload(Map.of("carrierRef", "t", "previousLocation", "d"))
            .previousHash("h1").eventHash("h2").build();
            
        TraceabilityEventDocument doc1 = TraceabilityEventDocument.builder()
            .eventId("id1").streamId("stream-ord").aggregateType("T").sequence(1)
            .eventType("ASSET_REGISTERED").payload(Map.of("assetId", "1", "assetType", "T", "quantity", 100, "unitOfMeasure", "DOSES", "currentLocation", "l", "custodianRef", "c"))
            .previousHash(DomainEvent.GENESIS_HASH).eventHash("h1").build();

        mongoTemplate.insert(doc2);
        mongoTemplate.insert(doc1);

        List<DomainEvent> events = eventStoreAdapter.loadStream("stream-ord");
        assertEquals(2, events.size());
        assertEquals("ASSET_REGISTERED", events.get(0).eventType().name());
        assertEquals("ASSET_DISPATCHED", events.get(1).eventType().name());
    }

    @Test
    void testReplayRealE2E() throws Exception {
        // 1. REGISTER
        AssetRegisteredPayload payload1 = new AssetRegisteredPayload("asset-e2e", "VACCINE", 100, "DOSES", "loc-A", "cust-A", null, null, null, null);
        DomainEvent event1 = new DomainEvent(() -> "ASSET_REGISTERED", payload1, Instant.now());
        eventStoreAdapter.append("asset-e2e", "PhysicalAsset", 0, event1, "actor-1");

        // 2. DISPATCH
        AssetDispatchedPayload payload2 = new AssetDispatchedPayload("trans-1", "loc-A");
        DomainEvent event2 = new DomainEvent(() -> "ASSET_DISPATCHED", payload2, Instant.now());
        eventStoreAdapter.append("asset-e2e", "PhysicalAsset", 1, event2, "actor-1");

        // 3. SPLIT
        AssetSplitPayload payload3 = new AssetSplitPayload("asset-e2e-child", 20, "DOSES", 100, 80, "DISPATCHED", null, null, null);
        DomainEvent event3 = new DomainEvent(() -> "ASSET_SPLIT", payload3, Instant.now());
        eventStoreAdapter.append("asset-e2e", "PhysicalAsset", 2, event3, "actor-1");

        // 4. Load Stream
        List<DomainEvent> events = eventStoreAdapter.loadStream("asset-e2e");
        List<DomainEventPayload> payloads = events.stream().map(DomainEvent::payload).collect(Collectors.toList());

        // 5. Rehydrate Aggregate using legitimate factory method
        PhysicalAsset aggregate = PhysicalAsset.rehydrate("asset-e2e", payloads, events.size()); 

        // 6. Verify State
        assertEquals("asset-e2e", aggregate.getAssetId());
        assertEquals(80, aggregate.getQuantity()); 
        assertEquals(AssetLifecycleStatus.DISPATCHED, aggregate.getLifecycleStatus());
        assertEquals("loc-A", aggregate.getLastKnownLocation()); 
        assertEquals("trans-1", aggregate.getCustodianRef());
        assertEquals(3, aggregate.getVersion());
    }
}
