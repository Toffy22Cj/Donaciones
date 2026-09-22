package com.traceability.core.infrastructure.persistence.mongo;

import com.traceability.contracts.SequenceRange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import org.springframework.boot.test.mock.mockito.MockBean;
import com.traceability.contracts.HashPort;

@SpringBootTest
@Testcontainers
class MongoUnanchoredEventAdapterTest {

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
    }

    @Autowired
    private MongoTemplate mongoTemplate;

    @MockBean
    private HashPort hashPort;

    @Autowired
    private MongoUnanchoredEventAdapter adapter;

    @BeforeEach
    void setUp() {
        mongoTemplate.dropCollection(TraceabilityEventDocument.class);
    }

    @AfterEach
    void tearDown() {
        mongoTemplate.dropCollection(TraceabilityEventDocument.class);
    }


    @Test
    void claimOrphansAndAssignBatch_preventsStarvation() {
        // Arrange
        // stream-A con 900 huérfanos
        List<TraceabilityEventDocument> events = new ArrayList<>();
        for (long seq = 1; seq <= 900; seq++) {
            events.add(createEvent("stream-A", seq));
        }
        // stream-Z con 50 huérfanos
        for (long seq = 1; seq <= 50; seq++) {
            events.add(createEvent("stream-Z", seq));
        }
        mongoTemplate.insertAll(events);

        // Act
        String batchId = "batch-123";
        int maxStreams = 2;
        int maxEventsPerBatch = 100;
        
        // El presupuesto total de 100 eventos se divide entre 2 streams elegibles:
        // stream-A debería obtener limit=50
        // stream-Z debería obtener limit=50
        Map<String, SequenceRange> coverage = adapter.claimOrphansAndAssignBatch(batchId, maxStreams, maxEventsPerBatch);

        // Assert
        assertNotNull(coverage);
        assertEquals(2, coverage.size(), "Ambos streams deben haber recibido cobertura");
        
        assertTrue(coverage.containsKey("stream-A"));
        assertTrue(coverage.containsKey("stream-Z"));

        // Verificamos que se repartieron exactamente 50 eventos a cada uno
        assertEquals(1, coverage.get("stream-A").fromSequence());
        assertEquals(50, coverage.get("stream-A").toSequence());

        assertEquals(1, coverage.get("stream-Z").fromSequence());
        assertEquals(50, coverage.get("stream-Z").toSequence());
    }
    
    @Test
    void claimOrphansAndAssignBatch_preservesContiguity() {
        // Arrange
        List<TraceabilityEventDocument> events = new ArrayList<>();
        for (long seq = 1; seq <= 150; seq++) {
            events.add(createEvent("stream-X", seq));
        }
        mongoTemplate.insertAll(events);
        
        String batchId1 = "batch-1";
        String batchId2 = "batch-2";
        int maxStreams = 1;
        int maxEventsPerBatch = 100;
        
        // Act - First pass
        Map<String, SequenceRange> coverage1 = adapter.claimOrphansAndAssignBatch(batchId1, maxStreams, maxEventsPerBatch);
        
        // Assert - First pass
        assertEquals(1, coverage1.size());
        assertTrue(coverage1.containsKey("stream-X"));
        assertEquals(1, coverage1.get("stream-X").fromSequence());
        assertEquals(100, coverage1.get("stream-X").toSequence());
        
        // Act - Second pass
        Map<String, SequenceRange> coverage2 = adapter.claimOrphansAndAssignBatch(batchId2, maxStreams, maxEventsPerBatch);
        
        // Assert - Second pass
        assertEquals(1, coverage2.size());
        assertTrue(coverage2.containsKey("stream-X"));
        assertEquals(101, coverage2.get("stream-X").fromSequence());
        assertEquals(150, coverage2.get("stream-X").toSequence());
    }

    @Test
    void claimOrphansAndAssignBatch_throwsExceptionOnConcurrencyFailure() {
        // Arrange
        TraceabilityEventDocument event = createEvent("stream-X", 1L);
        mongoTemplate.insert(event);
        
        String batchId = "batch-1";
        
        // We want to force a condition where `find` returns the event, 
        // but before `updateMulti` executes, the database is modified.
        // We use Mockito to spy on the mongoTemplate and intercept the updateMulti call.
        MongoTemplate spyTemplate = org.mockito.Mockito.spy(mongoTemplate);
        org.mockito.Mockito.doAnswer(invocation -> {
            // Simulate race condition: another thread steals the event
            org.springframework.data.mongodb.core.query.Query q = new org.springframework.data.mongodb.core.query.Query(
                org.springframework.data.mongodb.core.query.Criteria.where("_id").is(event.getEventId())
            );
            org.springframework.data.mongodb.core.query.Update u = new org.springframework.data.mongodb.core.query.Update().set("merkleBatchId", "stolen-batch");
            mongoTemplate.updateFirst(q, u, TraceabilityEventDocument.class);
            
            // Proceed with the original updateMulti
            return invocation.callRealMethod();
        }).when(spyTemplate).updateMulti(
            org.mockito.ArgumentMatchers.any(org.springframework.data.mongodb.core.query.Query.class), 
            org.mockito.ArgumentMatchers.any(org.springframework.data.mongodb.core.query.Update.class), 
            org.mockito.ArgumentMatchers.eq(TraceabilityEventDocument.class)
        );
        
        MongoUnanchoredEventAdapter testAdapter = new MongoUnanchoredEventAdapter(spyTemplate);
        
        // Act & Assert
        com.traceability.core.domain.exception.OrphanClaimConcurrencyException exception = assertThrows(
            com.traceability.core.domain.exception.OrphanClaimConcurrencyException.class, 
            () -> testAdapter.claimOrphansAndAssignBatch(batchId, 1, 100)
        );
        
        assertTrue(exception.getMessage().contains("tried to claim 1 but only modified 0"));
    }

    private TraceabilityEventDocument createEvent(String streamId, long sequence) {
        TraceabilityEventDocument doc = new TraceabilityEventDocument();
        doc.setEventId(UUID.randomUUID().toString());
        doc.setStreamId(streamId);
        doc.setSequence(sequence);
        doc.setAggregateType("PhysicalAsset");
        doc.setPayload(Map.of("dummy", true));
        doc.setActorRef(new com.traceability.core.domain.event.SystemActor("test"));
        doc.setOccurredAt(Instant.now().toString());
        doc.setRecordedAt(Instant.now().toString());
        doc.setEventHash("hash-" + streamId + "-" + sequence);
        // merkleBatchId is null to make it an orphan
        return doc;
    }
}
