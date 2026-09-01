package com.traceability.core.application.projection;

import com.traceability.core.application.event.EventCanonicalMapper;
import com.traceability.core.infrastructure.persistence.mongo.TraceabilityEventDocument;
import com.traceability.core.infrastructure.projection.mongo.documents.AssetHistoryProjectionDocument;
import com.traceability.core.infrastructure.projection.mongo.documents.DonationProjectionDocument;
import com.traceability.core.infrastructure.projection.mongo.documents.ProjectionRetryDocument;
import com.traceability.core.infrastructure.projection.mongo.repositories.AssetHistoryProjectionRepository;
import com.traceability.core.infrastructure.projection.mongo.repositories.AssetIndexRepository;
import com.traceability.core.infrastructure.projection.mongo.repositories.DonationProjectionRepository;
import com.traceability.core.infrastructure.projection.mongo.repositories.ProjectionRetryRepository;
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

import org.springframework.boot.test.mock.mockito.MockBean;
import com.traceability.contracts.HashPort;
import com.traceability.core.application.port.out.OutboxPort;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
class DonationProjectionIntegrationTest {

    @MockBean
    private HashPort hashPort;
    
    @MockBean
    private OutboxPort outboxPort;

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
    static class TestConfig {}

    @Autowired
    private DonationProjectionHandler projectionHandler;
    
    @Autowired
    private ProjectionRetryScheduler retryScheduler;

    @Autowired
    private DonationProjectionRepository projectionRepository;

    @Autowired
    private AssetIndexRepository assetIndexRepository;

    @Autowired
    private AssetHistoryProjectionRepository historyRepository;

    @Autowired
    private ProjectionRetryRepository retryRepository;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private EventCanonicalMapper canonicalMapper;

    @BeforeEach
    void setup() {
        cleanDb();
    }

    @AfterEach
    void clean() {
        cleanDb();
    }
    
    private void cleanDb() {
        projectionRepository.deleteAll();
        assetIndexRepository.deleteAll();
        historyRepository.deleteAll();
        retryRepository.deleteAll();
        mongoTemplate.dropCollection(TraceabilityEventDocument.class);
    }

    private TraceabilityEventDocument buildEvent(String streamId, String aggType, long seq, String type, Map<String, Object> payload) {
        TraceabilityEventDocument doc = new TraceabilityEventDocument();
        doc.setEventId(UUID.randomUUID().toString());
        doc.setStreamId(streamId);
        doc.setAggregateType(aggType);
        doc.setSequence(seq);
        doc.setEventType(type);
        doc.setPayload(payload);
        doc.setOccurredAt("2026-09-01T10:00:00Z");
        return doc;
    }

    @Test
    void testAssetSplit() {
        // 1. FUND_REGISTERED
        projectionHandler.handleEvent(buildEvent("fund-1", "Fund", 0, "FUND_REGISTERED", Map.of("pledgedAmount", 1000)));
        // 2. ALLOCATION_REQUESTED
        projectionHandler.handleEvent(buildEvent("fund-1", "Fund", 1, "ALLOCATION_REQUESTED", Map.of("allocationId", "alloc-1", "requestedAmount", 500)));
        
        // 3. ASSET_REGISTERED (Root)
        projectionHandler.handleEvent(buildEvent("asset-root", "PhysicalAsset", 0, "ASSET_REGISTERED", 
            Map.of("assetId", "asset-root", "allocationId", "alloc-1", "quantity", 100)));
            
        // 4. ASSET_SPLIT
        projectionHandler.handleEvent(buildEvent("asset-root", "PhysicalAsset", 1, "ASSET_SPLIT", 
            Map.of("parentQuantityAfter", 80L)));
            
        // 5. ASSET_REGISTERED (Child)
        projectionHandler.handleEvent(buildEvent("asset-child", "PhysicalAsset", 0, "ASSET_REGISTERED", 
            Map.of("assetId", "asset-child", "sourceAllocationId", "alloc-1", "parentAssetRef", "asset-root", "rootAssetRef", "asset-root", "quantity", 20)));

        DonationProjectionDocument proj = projectionRepository.findById("fund-1").get();
        assertEquals(2, proj.getLogistics().size());
        
        DonationProjectionDocument.LogisticsProjection rootLog = proj.getLogistics().stream().filter(l -> l.getAssetId().equals("asset-root")).findFirst().get();
        assertEquals(80, rootLog.getQuantity());
        assertEquals("alloc-1", rootLog.getAllocationId());
        
        DonationProjectionDocument.LogisticsProjection childLog = proj.getLogistics().stream().filter(l -> l.getAssetId().equals("asset-child")).findFirst().get();
        assertEquals(20, childLog.getQuantity());
        assertEquals("asset-root", childLog.getParentAssetRef());
        assertEquals("asset-root", childLog.getRootAssetRef());
        assertEquals("alloc-1", childLog.getSourceAllocationId());
    }

    @Test
    void testDuplicateEventIdempotency() {
        TraceabilityEventDocument ev1 = buildEvent("fund-2", "Fund", 0, "FUND_REGISTERED", Map.of("pledgedAmount", 1000));
        projectionHandler.handleEvent(ev1);
        
        TraceabilityEventDocument ev2 = buildEvent("fund-2", "Fund", 1, "FUNDS_CLEARED", Map.of("clearedAmount", 500));
        projectionHandler.handleEvent(ev2);
        
        DonationProjectionDocument proj = projectionRepository.findById("fund-2").get();
        assertEquals(500, proj.getFinancialSnapshot().getClearedAmount());
        assertEquals(1, proj.getAuditMetadata().getFundLastProcessedSequence());

        // Process duplicate
        projectionHandler.handleEvent(ev2);
        
        proj = projectionRepository.findById("fund-2").get();
        assertEquals(500, proj.getFinancialSnapshot().getClearedAmount()); // Still 500, not 1000
    }

    @Test
    void testGapAndRetry() {
        projectionHandler.handleEvent(buildEvent("fund-3", "Fund", 0, "FUND_REGISTERED", Map.of("pledgedAmount", 1000)));
        
        // Sequence 2 arrives before 1 (GAP)
        TraceabilityEventDocument ev2 = buildEvent("fund-3", "Fund", 2, "ALLOCATION_REQUESTED", Map.of("allocationId", "alloc-1", "requestedAmount", 300));
        projectionHandler.handleEvent(ev2);
        
        DonationProjectionDocument proj = projectionRepository.findById("fund-3").get();
        assertEquals(0, proj.getAllocations().size());
        
        List<ProjectionRetryDocument> pending = retryRepository.findByStatus("PENDING");
        assertEquals(1, pending.size());
        
        // Now sequence 1 arrives
        TraceabilityEventDocument ev1 = buildEvent("fund-3", "Fund", 1, "FUNDS_CLEARED", Map.of("clearedAmount", 500));
        projectionHandler.handleEvent(ev1);
        
        // Process retries
        retryScheduler.processRetries();
        
        proj = projectionRepository.findById("fund-3").get();
        assertEquals(500, proj.getFinancialSnapshot().getClearedAmount());
        assertEquals(1, proj.getAllocations().size());
        assertEquals(2, proj.getAuditMetadata().getFundLastProcessedSequence());
        
        assertEquals(0, retryRepository.findByStatus("PENDING").size());
    }

    @Autowired
    private ProjectionRebuildService rebuildService;

    @Autowired
    private com.traceability.core.infrastructure.projection.ProjectionEventSource eventSource;

    @Test
    void testRebuildWithConcurrentEvent() throws InterruptedException {
        // Ensure eventSource is stopped initially to avoid it picking up events immediately
        eventSource.stop();
        
        TraceabilityEventDocument ev0 = buildEvent("fund-concurrent", "Fund", 0, "FUND_REGISTERED", Map.of("pledgedAmount", 1000));
        mongoTemplate.insert(ev0);
        
        // 1. Get the token manually to simulate step 1
        org.bson.BsonDocument resumeToken;
        try (com.mongodb.client.MongoChangeStreamCursor<com.mongodb.client.model.changestream.ChangeStreamDocument<org.bson.Document>> cursor = (com.mongodb.client.MongoChangeStreamCursor<com.mongodb.client.model.changestream.ChangeStreamDocument<org.bson.Document>>) mongoTemplate.getCollection("event_store").watch().iterator()) {
            cursor.tryNext(); // Force initialization of resume token
            resumeToken = cursor.getResumeToken();
        }

        // 2. CONCURRENT event arriving AFTER token but BEFORE bulk read
        // FUNDS_CLEARED ADDS to the clearedAmount. So 1 processing = 500. 2 processings = 1000.
        TraceabilityEventDocument ev1 = buildEvent("fund-concurrent", "Fund", 1, "FUNDS_CLEARED", Map.of("clearedAmount", 500));
        mongoTemplate.insert(ev1);

        // 3. Read historical bulk USING A REAL QUERY.
        // This find() will NATURALLY pick up ev1 because it's already in the DB.
        List<TraceabilityEventDocument> historicalEvents = mongoTemplate.find(
            new org.springframework.data.mongodb.core.query.Query(org.springframework.data.mongodb.core.query.Criteria.where("streamId").is("fund-concurrent"))
                .with(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.ASC, "occurredAt")), 
            TraceabilityEventDocument.class
        );
        assertEquals(2, historicalEvents.size()); // Both ev0 and ev1 are picked up!
        
        for (TraceabilityEventDocument ev : historicalEvents) {
            projectionHandler.handleEvent(ev);
        }
        
        // Assert state after bulk read
        DonationProjectionDocument proj = projectionRepository.findById("fund-concurrent").get();
        assertEquals(500, proj.getFinancialSnapshot().getClearedAmount()); // Ev1 PROCESSED!
        assertEquals(1, proj.getAuditMetadata().getFundLastProcessedSequence());

        // 4. Switch to Change Stream using the token from Step 1.
        // Because the token is from BEFORE ev1 was inserted, the Change Stream WILL deliver ev1 AGAIN.
        if (resumeToken != null) {
            String tokenString = resumeToken.getString("_data").getValue();
            com.traceability.core.infrastructure.projection.mongo.documents.ProjectionCheckpointDocument cp = 
                new com.traceability.core.infrastructure.projection.mongo.documents.ProjectionCheckpointDocument("donation_projection_stream", tokenString);
            mongoTemplate.save(cp);
        }
        
        // Restart the event source, which will resume from the token and deliver ev1 a second time.
        eventSource.start();
        
        // Wait for asynchronous processing of the Change Stream.
        // We wait a fixed time to allow the stream to process the duplicate event.
        Thread.sleep(3000);
        
        // 5. Verify that the Idempotency Guard worked!
        // If ev1 was processed twice, clearedAmount would be 1000.
        // If the idempotency guard (sequence <= lastProcessedSequence) worked, it should still be 500.
        DonationProjectionDocument finalProj = projectionRepository.findById("fund-concurrent").get();
        assertEquals(500, finalProj.getFinancialSnapshot().getClearedAmount()); // Idempotency successful!
        assertEquals(1, finalProj.getAuditMetadata().getFundLastProcessedSequence()); // Sequence unchanged
        
        eventSource.stop();
    }

    @Test
    void testPauseAndResume() {
        projectionHandler.handleEvent(buildEvent("fund-5", "Fund", 0, "FUND_REGISTERED", Map.of("pledgedAmount", 1000)));
        
        DonationProjectionDocument proj = projectionRepository.findById("fund-5").get();
        proj.setStatus("PAUSED");
        projectionRepository.save(proj);
        
        TraceabilityEventDocument ev2 = buildEvent("fund-5", "Fund", 2, "ALLOCATION_REQUESTED", Map.of("allocationId", "alloc-1", "requestedAmount", 300));
        TraceabilityEventDocument ev3 = buildEvent("fund-5", "Fund", 3, "FUNDS_REFUNDED", Map.of("refundId", "ref-1", "refundAmount", 100));
        TraceabilityEventDocument ev1 = buildEvent("fund-5", "Fund", 1, "FUNDS_CLEARED", Map.of("clearedAmount", 500));
        
        projectionHandler.handleEvent(ev2);
        projectionHandler.handleEvent(ev3);
        projectionHandler.handleEvent(ev1);
        
        List<ProjectionRetryDocument> quarantined = retryRepository.findByProjectionIdAndStatusOrderBySequenceAsc("fund-5", "QUARANTINED");
        assertEquals(3, quarantined.size());
        
        // Resume
        retryScheduler.resumeProjection("fund-5");
        
        proj = projectionRepository.findById("fund-5").get();
        assertEquals("ACTIVE", proj.getStatus());
        assertEquals(500, proj.getFinancialSnapshot().getClearedAmount());
        assertEquals(1, proj.getAllocations().size());
        assertEquals(100, proj.getFinancialSnapshot().getRefundedAmount());
        assertEquals(3, proj.getAuditMetadata().getFundLastProcessedSequence());
        assertEquals(0, retryRepository.findAll().size());
    }
}
