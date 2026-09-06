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
import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
    "core.projection.retry.delay=100",
    "core.projection.retry.timeout-minutes=5"
})
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
    @org.springframework.scheduling.annotation.EnableScheduling
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
        assertEquals(0, new BigDecimal("80.0000").compareTo(rootLog.getQuantity()));
        assertEquals("alloc-1", rootLog.getAllocationId());
        
        DonationProjectionDocument.LogisticsProjection childLog = proj.getLogistics().stream().filter(l -> l.getAssetId().equals("asset-child")).findFirst().get();
        assertEquals(0, new BigDecimal("20.0000").compareTo(childLog.getQuantity()));
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
        
        // Wait for scheduler to process retries automatically
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10))
            .until(() -> retryRepository.findByStatus("PENDING").isEmpty());
        
        proj = projectionRepository.findById("fund-3").get();
        assertEquals(500, proj.getFinancialSnapshot().getClearedAmount());
        assertEquals(1, proj.getAllocations().size());
        assertEquals(2, proj.getAuditMetadata().getFundLastProcessedSequence());
        
        assertEquals(0, retryRepository.findByStatus("PENDING").size());
    }

    @Test
    void testStuckProcessingRescue() {
        // Make sure the prior event is there so it can succeed
        projectionHandler.handleEvent(buildEvent("fund-stuck", "Fund", 0, "FUND_REGISTERED", Map.of("pledgedAmount", 1000L)));

        // Insert a document in PROCESSING that started 10 minutes ago
        ProjectionRetryDocument stuckDoc = ProjectionRetryDocument.builder()
            .id("event-stuck_DonationProjectionHandler")
            .handlerName("DonationProjectionHandler")
            .eventId("event-stuck")
            .streamId("fund-stuck")
            .sequence(1)
            .eventType("FUNDS_CLEARED")
            .payload(Map.of("clearedAmount", 500L))
            .occurredAt("2026-09-01T10:00:00Z")
            .firstAttemptAt("2026-09-01T10:00:00Z")
            .lastAttemptAt("2026-09-01T10:00:00Z")
            .status("PROCESSING")
            .build();
        stuckDoc.setProcessingStartedAt(java.time.Instant.now().minus(10, java.time.temporal.ChronoUnit.MINUTES).toString());
        retryRepository.save(stuckDoc);

        // Await processing (scheduler should pick it up because it timed out)
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10))
            .until(() -> retryRepository.findById("event-stuck_DonationProjectionHandler").isEmpty());

        DonationProjectionDocument proj = projectionRepository.findById("fund-stuck").get();
        assertEquals(500L, proj.getFinancialSnapshot().getClearedAmount());
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

    // --- Grupo A ---
    @Test
    void testA1_FundRegisteredOriginalAmount() {
        projectionHandler.handleEvent(buildEvent("fund-a1", "Fund", 0, "FUND_REGISTERED", Map.of("pledgedAmount", 500000L)));
        DonationProjectionDocument proj = projectionRepository.findById("fund-a1").get();
        assertEquals(500000L, proj.getFinancialSnapshot().getOriginalAmount());
    }

    @Test
    void testA2_FundsClearedGenesisOriginalAmount() {
        projectionHandler.handleEvent(buildEvent("fund-a2", "Fund", 0, "FUNDS_CLEARED", Map.of("clearedAmount", 300000L)));
        DonationProjectionDocument proj = projectionRepository.findById("fund-a2").get();
        assertEquals(300000L, proj.getFinancialSnapshot().getOriginalAmount());
        assertEquals(300000L, proj.getFinancialSnapshot().getClearedAmount());
    }

    @Test
    void testA3_FundRegisteredThenFundsClearedOriginalAmount() {
        projectionHandler.handleEvent(buildEvent("fund-a3", "Fund", 0, "FUND_REGISTERED", Map.of("pledgedAmount", 500000L)));
        projectionHandler.handleEvent(buildEvent("fund-a3", "Fund", 1, "FUNDS_CLEARED", Map.of("clearedAmount", 500000L)));
        DonationProjectionDocument proj = projectionRepository.findById("fund-a3").get();
        assertEquals(500000L, proj.getFinancialSnapshot().getOriginalAmount());
        assertEquals(500000L, proj.getFinancialSnapshot().getClearedAmount());
    }

    // --- Grupo B ---
    @Test
    void testB1_AllocationRequestedPendingStatus() {
        projectionHandler.handleEvent(buildEvent("fund-b", "Fund", 0, "FUND_REGISTERED", Map.of("pledgedAmount", 500000L)));
        projectionHandler.handleEvent(buildEvent("fund-b", "Fund", 1, "ALLOCATION_REQUESTED", Map.of("allocationId", "A1", "requestedAmount", 100000L)));
        DonationProjectionDocument proj = projectionRepository.findById("fund-b").get();
        assertEquals(1, proj.getAllocations().size());
        assertEquals("PENDING", proj.getAllocations().get(0).getStatus());
        assertEquals(100000L, proj.getFinancialSnapshot().getPendingAllocationAmount());
    }

    @Test
    void testB2_AllocationConfirmedStatus() {
        projectionHandler.handleEvent(buildEvent("fund-b2", "Fund", 0, "FUND_REGISTERED", Map.of("pledgedAmount", 500000L)));
        projectionHandler.handleEvent(buildEvent("fund-b2", "Fund", 1, "ALLOCATION_REQUESTED", Map.of("allocationId", "A1", "requestedAmount", 100000L)));
        projectionHandler.handleEvent(buildEvent("fund-b2", "Fund", 2, "ALLOCATION_CONFIRMED", Map.of("allocationId", "A1")));
        DonationProjectionDocument proj = projectionRepository.findById("fund-b2").get();
        assertEquals(1, proj.getAllocations().size());
        assertEquals("CONFIRMED", proj.getAllocations().get(0).getStatus());
        assertEquals(0L, proj.getFinancialSnapshot().getPendingAllocationAmount());
    }

    @Test
    void testB3_AllocationReversedFromPending() {
        projectionHandler.handleEvent(buildEvent("fund-b3", "Fund", 0, "FUND_REGISTERED", Map.of("pledgedAmount", 500000L)));
        projectionHandler.handleEvent(buildEvent("fund-b3", "Fund", 1, "ALLOCATION_REQUESTED", Map.of("allocationId", "A1", "requestedAmount", 100000L)));
        projectionHandler.handleEvent(buildEvent("fund-b3", "Fund", 2, "ALLOCATION_REVERSED", Map.of("allocationId", "A1")));
        DonationProjectionDocument proj = projectionRepository.findById("fund-b3").get();
        assertEquals(0, proj.getAllocations().size());
        assertEquals(0L, proj.getFinancialSnapshot().getPendingAllocationAmount());
    }

    @Test
    void testB4_AllocationReversedFromConfirmed() {
        projectionHandler.handleEvent(buildEvent("fund-b4", "Fund", 0, "FUND_REGISTERED", Map.of("pledgedAmount", 500000L)));
        projectionHandler.handleEvent(buildEvent("fund-b4", "Fund", 1, "ALLOCATION_REQUESTED", Map.of("allocationId", "A1", "requestedAmount", 100000L)));
        projectionHandler.handleEvent(buildEvent("fund-b4", "Fund", 2, "ALLOCATION_CONFIRMED", Map.of("allocationId", "A1")));
        projectionHandler.handleEvent(buildEvent("fund-b4", "Fund", 3, "ALLOCATION_REVERSED", Map.of("allocationId", "A1")));
        DonationProjectionDocument proj = projectionRepository.findById("fund-b4").get();
        assertEquals(0, proj.getAllocations().size());
        assertEquals(0L, proj.getFinancialSnapshot().getPendingAllocationAmount());
    }

    @Test
    void testB5_AllocationActionOnNonExistent() {
        projectionHandler.handleEvent(buildEvent("fund-b5", "Fund", 0, "FUND_REGISTERED", Map.of("pledgedAmount", 500000L)));
        projectionHandler.handleEvent(buildEvent("fund-b5", "Fund", 1, "ALLOCATION_CONFIRMED", Map.of("allocationId", "A-INVALID")));
        projectionHandler.handleEvent(buildEvent("fund-b5", "Fund", 2, "ALLOCATION_REVERSED", Map.of("allocationId", "A-INVALID")));
        DonationProjectionDocument proj = projectionRepository.findById("fund-b5").get();
        assertEquals(0, proj.getAllocations().size());
        assertEquals(0L, proj.getFinancialSnapshot().getPendingAllocationAmount());
    }

    // --- Grupo C ---
    @Test
    void testC1_ConfirmedAmountCalculation() {
        projectionHandler.handleEvent(buildEvent("fund-c1", "Fund", 0, "FUND_REGISTERED", Map.of("pledgedAmount", 500000L)));
        projectionHandler.handleEvent(buildEvent("fund-c1", "Fund", 1, "ALLOCATION_REQUESTED", Map.of("allocationId", "A1", "requestedAmount", 100000L)));
        projectionHandler.handleEvent(buildEvent("fund-c1", "Fund", 2, "ALLOCATION_CONFIRMED", Map.of("allocationId", "A1")));
        projectionHandler.handleEvent(buildEvent("fund-c1", "Fund", 3, "ALLOCATION_REQUESTED", Map.of("allocationId", "A2", "requestedAmount", 50000L)));
        projectionHandler.handleEvent(buildEvent("fund-c1", "Fund", 4, "ALLOCATION_REQUESTED", Map.of("allocationId", "A3", "requestedAmount", 200000L)));
        projectionHandler.handleEvent(buildEvent("fund-c1", "Fund", 5, "ALLOCATION_CONFIRMED", Map.of("allocationId", "A3")));
        
        DonationProjectionDocument proj = projectionRepository.findById("fund-c1").get();
        
        long confirmedAmount = proj.getAllocations().stream()
                .filter(a -> "CONFIRMED".equals(a.getStatus()))
                .mapToLong(a -> a.getAmount())
                .sum();
        
        assertEquals(300000L, confirmedAmount);
    }
    
    @Test
    void testC2_NoPersistedAggregatedField() {
        projectionHandler.handleEvent(buildEvent("fund-c2", "Fund", 0, "FUND_REGISTERED", Map.of("pledgedAmount", 500000L)));
        projectionHandler.handleEvent(buildEvent("fund-c2", "Fund", 1, "ALLOCATION_REQUESTED", Map.of("allocationId", "A1", "requestedAmount", 100000L)));
        projectionHandler.handleEvent(buildEvent("fund-c2", "Fund", 2, "ALLOCATION_CONFIRMED", Map.of("allocationId", "A1")));
        
        org.bson.Document doc = mongoTemplate.findById("fund-c2", org.bson.Document.class, "donation_projections");
        org.bson.Document financialSnapshot = doc.get("financialSnapshot", org.bson.Document.class);
        
        assertNull(financialSnapshot.get("allocatedAmount"));
        assertNull(financialSnapshot.get("confirmedAllocationAmount"));
        assertNull(doc.get("allocatedAmount"));
        assertNull(doc.get("confirmedAllocationAmount"));
    }

    // --- Grupo D ---
    @Test
    void testD1_RebuildOriginalAmountFix() {
        // Create corrupted genesis explicitly using MongoTemplate
        DonationProjectionDocument doc = new DonationProjectionDocument();
        doc.setProjectionId("fund-d1");
        doc.getAuditMetadata().setFundLastProcessedSequence(0);
        doc.getFinancialSnapshot().setClearedAmount(300000L);
        // deliberately leaving originalAmount = 0
        mongoTemplate.save(doc);
        
        mongoTemplate.insert(buildEvent("fund-d1", "Fund", 0, "FUNDS_CLEARED", Map.of("clearedAmount", 300000L)));
        
        rebuildService.rebuildAll();
        
        DonationProjectionDocument rebuilt = projectionRepository.findById("fund-d1").get();
        assertEquals(300000L, rebuilt.getFinancialSnapshot().getOriginalAmount());
    }

    @Test
    void testD2_RebuildAllocationStatusFix() {
        // Create corrupted allocations (no status) explicitly using MongoTemplate
        DonationProjectionDocument doc = new DonationProjectionDocument();
        doc.setProjectionId("fund-d2");
        doc.getAuditMetadata().setFundLastProcessedSequence(1);
        DonationProjectionDocument.AllocationProjection alloc = new DonationProjectionDocument.AllocationProjection();
        alloc.setAllocationId("A1");
        alloc.setAmount(100000L);
        // Deliberately not setting status
        doc.getAllocations().add(alloc);
        mongoTemplate.save(doc);
        
        mongoTemplate.insert(buildEvent("fund-d2", "Fund", 0, "FUND_REGISTERED", Map.of("pledgedAmount", 500000L)));
        mongoTemplate.insert(buildEvent("fund-d2", "Fund", 1, "ALLOCATION_REQUESTED", Map.of("allocationId", "A1", "requestedAmount", 100000L)));
        
        rebuildService.rebuildAll();
        
        DonationProjectionDocument rebuilt = projectionRepository.findById("fund-d2").get();
        assertEquals(1, rebuilt.getAllocations().size());
        assertEquals("PENDING", rebuilt.getAllocations().get(0).getStatus());
    }

    @Test
    void testD3_RebuildIdempotency() {
        mongoTemplate.insert(buildEvent("fund-d3", "Fund", 0, "FUND_REGISTERED", Map.of("pledgedAmount", 500000L)));
        mongoTemplate.insert(buildEvent("fund-d3", "Fund", 1, "ALLOCATION_REQUESTED", Map.of("allocationId", "A1", "requestedAmount", 100000L)));
        
        rebuildService.rebuildAll();
        DonationProjectionDocument firstRebuild = projectionRepository.findById("fund-d3").get();
        
        rebuildService.rebuildAll();
        DonationProjectionDocument secondRebuild = projectionRepository.findById("fund-d3").get();
        
        assertEquals(firstRebuild.getFinancialSnapshot().getOriginalAmount(), secondRebuild.getFinancialSnapshot().getOriginalAmount());
        assertEquals(1, secondRebuild.getAllocations().size());
        assertEquals("PENDING", secondRebuild.getAllocations().get(0).getStatus());
    }

    // --- Tarea 10.2 Grupo A ---
    @Test
    void testA1_AssetCustodyTransferred() {
        projectionHandler.handleEvent(buildEvent("fund-102a1", "Fund", 0, "FUND_REGISTERED", Map.of("pledgedAmount", 5000L)));
        projectionHandler.handleEvent(buildEvent("fund-102a1", "Fund", 1, "ALLOCATION_REQUESTED", Map.of("allocationId", "alloc-1", "requestedAmount", 1000L)));
        projectionHandler.handleEvent(buildEvent("asset-102a1", "PhysicalAsset", 0, "ASSET_REGISTERED", Map.of("assetId", "asset-102a1", "allocationId", "alloc-1", "quantity", 100L, "currentLocation", "LocA", "custodianRef", "CustA")));
        projectionHandler.handleEvent(buildEvent("asset-102a1", "PhysicalAsset", 1, "ASSET_CUSTODY_TRANSFERRED", Map.of("newCustodianRef", "CustB")));

        DonationProjectionDocument proj = projectionRepository.findById("fund-102a1").get();
        DonationProjectionDocument.LogisticsProjection log = proj.getLogistics().get(0);
        assertEquals("CustB", log.getCurrentCustodian());
        assertEquals("REGISTERED", log.getLifecycleStatus()); // Unchanged
    }

    @Test
    void testA2_AssetDelivered() {
        projectionHandler.handleEvent(buildEvent("fund-102a2", "Fund", 0, "FUND_REGISTERED", Map.of("pledgedAmount", 5000L)));
        projectionHandler.handleEvent(buildEvent("fund-102a2", "Fund", 1, "ALLOCATION_REQUESTED", Map.of("allocationId", "alloc-1", "requestedAmount", 1000L)));
        projectionHandler.handleEvent(buildEvent("asset-102a2", "PhysicalAsset", 0, "ASSET_REGISTERED", Map.of("assetId", "asset-102a2", "allocationId", "alloc-1", "quantity", 100L, "currentLocation", "LocA", "custodianRef", "CustA")));
        projectionHandler.handleEvent(buildEvent("asset-102a2", "PhysicalAsset", 1, "ASSET_DELIVERED", Map.of("locationRef", "LocB", "finalCustodianRef", "CustFinal", "beneficiaryRef", "Ben1")));

        DonationProjectionDocument proj = projectionRepository.findById("fund-102a2").get();
        DonationProjectionDocument.LogisticsProjection log = proj.getLogistics().get(0);
        assertEquals("LocB", log.getCurrentLocation());
        assertEquals("CustFinal", log.getCurrentCustodian());
        assertEquals("DELIVERED", log.getLifecycleStatus());

        org.bson.Document rawDoc = mongoTemplate.findById("fund-102a2", org.bson.Document.class, "donation_projections");
        java.util.List<org.bson.Document> logistics = rawDoc.getList("logistics", org.bson.Document.class);
        assertFalse(logistics.get(0).containsKey("beneficiaryRef"));
        
        AssetHistoryProjectionDocument hist = historyRepository.findById("asset-102a2").get();
        assertEquals("DELIVERED", hist.getTransitions().get(1).getStatus());
        org.bson.Document rawHist = mongoTemplate.findById("asset-102a2", org.bson.Document.class, "asset_history");
        java.util.List<org.bson.Document> trans = rawHist.getList("transitions", org.bson.Document.class);
        assertFalse(trans.get(1).containsKey("beneficiaryRef"));
    }

    @Test
    void testA3_AssetReceivedWithReceiverRef() {
        projectionHandler.handleEvent(buildEvent("fund-102a3", "Fund", 0, "FUND_REGISTERED", Map.of("pledgedAmount", 5000L)));
        projectionHandler.handleEvent(buildEvent("fund-102a3", "Fund", 1, "ALLOCATION_REQUESTED", Map.of("allocationId", "alloc-1", "requestedAmount", 1000L)));
        projectionHandler.handleEvent(buildEvent("asset-102a3", "PhysicalAsset", 0, "ASSET_REGISTERED", Map.of("assetId", "asset-102a3", "allocationId", "alloc-1", "quantity", 100L, "currentLocation", "LocA", "custodianRef", "CustA")));
        projectionHandler.handleEvent(buildEvent("asset-102a3", "PhysicalAsset", 1, "ASSET_RECEIVED", Map.of("facilityLocation", "LocC", "receiverRef", "CustC")));

        DonationProjectionDocument proj = projectionRepository.findById("fund-102a3").get();
        DonationProjectionDocument.LogisticsProjection log = proj.getLogistics().get(0);
        assertEquals("LocC", log.getCurrentLocation());
        assertEquals("CustC", log.getCurrentCustodian());
        assertEquals("RECEIVED", log.getLifecycleStatus());
    }

    @Test
    void testA4_AssetReceivedWithoutReceiverRef() {
        projectionHandler.handleEvent(buildEvent("fund-102a4", "Fund", 0, "FUND_REGISTERED", Map.of("pledgedAmount", 5000L)));
        projectionHandler.handleEvent(buildEvent("fund-102a4", "Fund", 1, "ALLOCATION_REQUESTED", Map.of("allocationId", "alloc-1", "requestedAmount", 1000L)));
        projectionHandler.handleEvent(buildEvent("asset-102a4", "PhysicalAsset", 0, "ASSET_REGISTERED", Map.of("assetId", "asset-102a4", "allocationId", "alloc-1", "quantity", 100L, "currentLocation", "LocA", "custodianRef", "CustA")));
        projectionHandler.handleEvent(buildEvent("asset-102a4", "PhysicalAsset", 1, "ASSET_RECEIVED", Map.of("facilityLocation", "LocD")));

        DonationProjectionDocument proj = projectionRepository.findById("fund-102a4").get();
        DonationProjectionDocument.LogisticsProjection log = proj.getLogistics().get(0);
        assertEquals("CustA", log.getCurrentCustodian()); // Preserved
        assertEquals("LocD", log.getCurrentLocation());
    }

    // --- Tarea 10.2 Grupo B ---
    @Test
    void testB1_AssetDepleted() {
        projectionHandler.handleEvent(buildEvent("fund-102b1", "Fund", 0, "FUND_REGISTERED", Map.of("pledgedAmount", 5000L)));
        projectionHandler.handleEvent(buildEvent("fund-102b1", "Fund", 1, "ALLOCATION_REQUESTED", Map.of("allocationId", "alloc-1", "requestedAmount", 1000L)));
        projectionHandler.handleEvent(buildEvent("asset-102b1", "PhysicalAsset", 0, "ASSET_REGISTERED", Map.of("assetId", "asset-102b1", "allocationId", "alloc-1", "quantity", 100L, "currentLocation", "LocA", "custodianRef", "CustA")));
        projectionHandler.handleEvent(buildEvent("asset-102b1", "PhysicalAsset", 1, "ASSET_SPLIT", Map.of("parentQuantityAfter", 0L, "statusBeforeSplit", "REGISTERED")));
        projectionHandler.handleEvent(buildEvent("asset-102b1", "PhysicalAsset", 2, "ASSET_DEPLETED", Map.of()));

        DonationProjectionDocument proj = projectionRepository.findById("fund-102b1").get();
        DonationProjectionDocument.LogisticsProjection log = proj.getLogistics().get(0);
        assertEquals(0, new BigDecimal("0.0000").compareTo(log.getQuantity()));
        assertEquals("DEPLETED", log.getLifecycleStatus());
    }

    @Test
    void testB2_AssetSplitCompensated() {
        projectionHandler.handleEvent(buildEvent("fund-102b2", "Fund", 0, "FUND_REGISTERED", Map.of("pledgedAmount", 5000L)));
        projectionHandler.handleEvent(buildEvent("fund-102b2", "Fund", 1, "ALLOCATION_REQUESTED", Map.of("allocationId", "alloc-1", "requestedAmount", 1000L)));
        projectionHandler.handleEvent(buildEvent("asset-102b2", "PhysicalAsset", 0, "ASSET_REGISTERED", Map.of("assetId", "asset-102b2", "allocationId", "alloc-1", "quantity", 100L, "currentLocation", "LocA", "custodianRef", "CustA")));
        projectionHandler.handleEvent(buildEvent("asset-102b2", "PhysicalAsset", 1, "ASSET_RECEIVED", Map.of("facilityLocation", "LocC")));
        projectionHandler.handleEvent(buildEvent("asset-102b2", "PhysicalAsset", 2, "ASSET_SPLIT", Map.of("parentQuantityAfter", 80L, "statusBeforeSplit", "RECEIVED")));

        DonationProjectionDocument proj = projectionRepository.findById("fund-102b2").get();
        assertEquals("RECEIVED", proj.getLogistics().get(0).getStatusBeforeSplit());

        projectionHandler.handleEvent(buildEvent("asset-102b2", "PhysicalAsset", 3, "ASSET_SPLIT_COMPENSATED", Map.of("reintegratedQuantity", 20L)));
        
        proj = projectionRepository.findById("fund-102b2").get();
        DonationProjectionDocument.LogisticsProjection log = proj.getLogistics().get(0);
        assertEquals(0, new BigDecimal("100.0000").compareTo(log.getQuantity()));
        assertEquals("RECEIVED", log.getLifecycleStatus());
        assertNull(log.getStatusBeforeSplit());
    }

    // --- Tarea 10.2 Grupo C ---
    @Test
    void testC1_RebuildFromScratch() {
        mongoTemplate.insert(buildEvent("fund-102c1", "Fund", 0, "FUND_REGISTERED", Map.of("pledgedAmount", 5000L)));
        mongoTemplate.insert(buildEvent("fund-102c1", "Fund", 1, "ALLOCATION_REQUESTED", Map.of("allocationId", "alloc-1", "requestedAmount", 1000L)));
        mongoTemplate.insert(buildEvent("asset-102c1", "PhysicalAsset", 0, "ASSET_REGISTERED", Map.of("assetId", "asset-102c1", "allocationId", "alloc-1", "quantity", 100L, "currentLocation", "LocA", "custodianRef", "CustA")));
        mongoTemplate.insert(buildEvent("asset-102c1", "PhysicalAsset", 1, "ASSET_DISPATCHED", Map.of("carrierRef", "CustB")));
        mongoTemplate.insert(buildEvent("asset-102c1", "PhysicalAsset", 2, "ASSET_RECEIVED", Map.of("facilityLocation", "LocC")));
        mongoTemplate.insert(buildEvent("asset-102c1", "PhysicalAsset", 3, "ASSET_SPLIT", Map.of("parentQuantityAfter", 80L, "statusBeforeSplit", "RECEIVED")));
        mongoTemplate.insert(buildEvent("asset-102c1", "PhysicalAsset", 4, "ASSET_SPLIT_COMPENSATED", Map.of("reintegratedQuantity", 20L)));
        mongoTemplate.insert(buildEvent("asset-102c1", "PhysicalAsset", 5, "ASSET_DELIVERED", Map.of("locationRef", "LocFinal", "finalCustodianRef", "CustFinal")));

        rebuildService.rebuildAll();

        DonationProjectionDocument proj = projectionRepository.findById("fund-102c1").get();
        DonationProjectionDocument.LogisticsProjection log = proj.getLogistics().get(0);
        assertEquals(0, new BigDecimal("100.0000").compareTo(log.getQuantity()));
        assertEquals("DELIVERED", log.getLifecycleStatus());
        assertEquals("LocFinal", log.getCurrentLocation());
        assertEquals("CustFinal", log.getCurrentCustodian());
        assertNull(log.getStatusBeforeSplit());
        
        AssetHistoryProjectionDocument hist = historyRepository.findById("asset-102c1").get();
        assertEquals(6, hist.getTransitions().size());
        assertEquals("REGISTERED", hist.getTransitions().get(0).getStatus());
        assertEquals("DISPATCHED", hist.getTransitions().get(1).getStatus());
        assertEquals("RECEIVED", hist.getTransitions().get(2).getStatus());
        assertEquals("SPLIT", hist.getTransitions().get(3).getStatus());
        assertEquals("SPLIT_COMPENSATED", hist.getTransitions().get(4).getStatus());
        assertEquals("DELIVERED", hist.getTransitions().get(5).getStatus());
    }

    @Test
    void testC2_RebuildIdempotency() {
        mongoTemplate.insert(buildEvent("fund-102c2", "Fund", 0, "FUND_REGISTERED", Map.of("pledgedAmount", 5000L)));
        mongoTemplate.insert(buildEvent("fund-102c2", "Fund", 1, "ALLOCATION_REQUESTED", Map.of("allocationId", "alloc-1", "requestedAmount", 1000L)));
        mongoTemplate.insert(buildEvent("asset-102c2", "PhysicalAsset", 0, "ASSET_REGISTERED", Map.of("assetId", "asset-102c2", "allocationId", "alloc-1", "quantity", 100L, "currentLocation", "LocA", "custodianRef", "CustA")));
        mongoTemplate.insert(buildEvent("asset-102c2", "PhysicalAsset", 1, "ASSET_DELIVERED", Map.of("locationRef", "LocFinal", "finalCustodianRef", "CustFinal")));

        rebuildService.rebuildAll();
        DonationProjectionDocument firstRebuild = projectionRepository.findById("fund-102c2").get();
        
        rebuildService.rebuildAll();
        DonationProjectionDocument secondRebuild = projectionRepository.findById("fund-102c2").get();
        
        assertEquals(firstRebuild.getLogistics().get(0).getLifecycleStatus(), secondRebuild.getLogistics().get(0).getLifecycleStatus());
        assertEquals("DELIVERED", secondRebuild.getLogistics().get(0).getLifecycleStatus());
    }

    @Test
    void testC3_RetroactiveReconstruction() {
        // Create corrupted genesis explicitly using MongoTemplate (simulate bug before fix)
        DonationProjectionDocument doc = new DonationProjectionDocument();
        doc.setProjectionId("fund-102c3");
        doc.getAuditMetadata().setFundLastProcessedSequence(0);
        doc.getAuditMetadata().getAssetLastProcessedSequences().put("asset-102c3", 1L);
        DonationProjectionDocument.LogisticsProjection log = new DonationProjectionDocument.LogisticsProjection();
        log.setAssetId("asset-102c3");
        log.setLifecycleStatus("RECEIVED"); // Stuck in RECEIVED
        log.setCurrentLocation("LocC");
        log.setCurrentCustodian("CustC");
        doc.getLogistics().add(log);
        mongoTemplate.save(doc);

        mongoTemplate.insert(buildEvent("fund-102c3", "Fund", 0, "FUND_REGISTERED", Map.of("pledgedAmount", 5000L)));
        mongoTemplate.insert(buildEvent("fund-102c3", "Fund", 1, "ALLOCATION_REQUESTED", Map.of("allocationId", "alloc-1", "requestedAmount", 1000L)));
        mongoTemplate.insert(buildEvent("asset-102c3", "PhysicalAsset", 0, "ASSET_REGISTERED", Map.of("assetId", "asset-102c3", "allocationId", "alloc-1", "quantity", 100L, "currentLocation", "LocA", "custodianRef", "CustA")));
        mongoTemplate.insert(buildEvent("asset-102c3", "PhysicalAsset", 1, "ASSET_DELIVERED", Map.of("locationRef", "LocFinal", "finalCustodianRef", "CustFinal")));

        rebuildService.rebuildAll();

        DonationProjectionDocument rebuilt = projectionRepository.findById("fund-102c3").get();
        DonationProjectionDocument.LogisticsProjection rebuiltLog = rebuilt.getLogistics().get(0);
        
        // Assert state was fixed!
        assertEquals("DELIVERED", rebuiltLog.getLifecycleStatus());
        assertEquals("LocFinal", rebuiltLog.getCurrentLocation());
        assertEquals("CustFinal", rebuiltLog.getCurrentCustodian());
    }

    // --- Hallazgo #4 ---
    @Test
    void testHallazgo4_GenesisCompleteness_PropagatesCurrencyAndCampaign() {
        projectionHandler.handleEvent(buildEvent("fund-h4-comp", "Fund", 0, "FUND_REGISTERED", 
            Map.of("pledgedAmount", 5000L, "currency", "COP", "campaignRef", "CAMP-1")));
            
        DonationProjectionDocument proj = projectionRepository.findById("fund-h4-comp").get();
        assertEquals("COP", proj.getCurrency());
        assertEquals("CAMP-1", proj.getCampaignRef());
    }

    @Test
    void testHallazgo4_Privacy_DonorRefIsNeverPersistedInRawMongo() {
        projectionHandler.handleEvent(buildEvent("fund-h4-priv", "Fund", 0, "FUND_REGISTERED", 
            Map.of("pledgedAmount", 5000L, "currency", "COP", "campaignRef", "CAMP-1", "donorRef", "DONOR-SECRET")));
            
        // Ensure donorRef is strictly absent in the raw MongoDB document
        org.bson.Document rawDoc = mongoTemplate.findById("fund-h4-priv", org.bson.Document.class, "donation_projections");
        assertNotNull(rawDoc, "The projection document must exist");
        assertFalse(rawDoc.containsKey("donorRef"), "donorRef MUST NEVER be stored in the public projection document");
    }
}
