package com.traceability.core.application.projection;

import com.traceability.contracts.HashPort;
import com.traceability.core.application.port.out.OutboxPort;
import com.traceability.core.domain.fund.AllocationStatus;
import com.traceability.core.infrastructure.persistence.mongo.TraceabilityEventDocument;
import com.traceability.core.infrastructure.projection.mongo.documents.PendingAllocationDocument;
import com.traceability.core.infrastructure.projection.mongo.repositories.PendingAllocationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.mock.mockito.MockBean;
import com.traceability.contracts.authorization.IdentityPrincipalPort;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
    PendingAllocationProjectionHandlerTest.DISABLE_SCHEDULER_PROP,
    "core.projection.retry.timeout-minutes=5"
})
@Testcontainers
class PendingAllocationProjectionHandlerTest {

    @MockBean
    private IdentityPrincipalPort identityPrincipalPort;

    static final String DISABLE_SCHEDULER_PROP = "core.projection.retry.delay=99999999";

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

    @Autowired
    private PendingAllocationProjectionHandler projectionHandler;

    @Autowired
    private PendingAllocationRepository repository;

    @Autowired
    private org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;

    @BeforeEach
    void setup() {
        cleanDb();
    }

    @AfterEach
    void clean() {
        cleanDb();
    }

    private void cleanDb() {
        repository.deleteAll();
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
        doc.setSchemaVersion("1.0");
        doc.setOccurredAt("2026-09-01T10:00:00Z");
        return doc;
    }

    @Test
    void test1_AllocationRequested_CreatesDocument() {
        projectionHandler.handleEvent(buildEvent("fund-1", "Fund", 1, "ALLOCATION_REQUESTED",
            Map.of("allocationId", "alloc-1", "requestedAmount", 500)));

        Optional<PendingAllocationDocument> docOpt = repository.findById("alloc-1");
        assertTrue(docOpt.isPresent());
        PendingAllocationDocument doc = docOpt.get();
        assertEquals("alloc-1", doc.getAllocationId());
        assertEquals("fund-1", doc.getFundId());
        assertEquals(500L, doc.getRequestedAmount());
        assertEquals(Instant.parse("2026-09-01T10:00:00Z"), doc.getRequestedAt());
        assertEquals(AllocationStatus.REQUESTED, doc.getStatus());
    }

    @Test
    void test2_SequenceDiscrimination_ConfirmedRemainsConfirmed() {
        // seq 1: ALLOCATION_REQUESTED allocation A
        projectionHandler.handleEvent(buildEvent("fund-1", "Fund", 1, "ALLOCATION_REQUESTED",
            Map.of("allocationId", "alloc-A", "requestedAmount", 500)));

        // seq 2: evento NO relacionado con allocation A dentro del mismo Fund
        projectionHandler.handleEvent(buildEvent("fund-1", "Fund", 2, "ALLOCATION_REQUESTED",
            Map.of("allocationId", "alloc-B", "requestedAmount", 300)));

        // seq 3: ALLOCATION_CONFIRMED allocation A
        projectionHandler.handleEvent(buildEvent("fund-1", "Fund", 3, "ALLOCATION_CONFIRMED",
            Map.of("allocationId", "alloc-A")));

        Optional<PendingAllocationDocument> docOptA = repository.findById("alloc-A");
        assertTrue(docOptA.isPresent());
        assertEquals(AllocationStatus.CONFIRMED, docOptA.get().getStatus());

        Optional<PendingAllocationDocument> docOptB = repository.findById("alloc-B");
        assertTrue(docOptB.isPresent());
        assertEquals(AllocationStatus.REQUESTED, docOptB.get().getStatus());
    }

    @Test
    void test3_AllocationReversed_StatusReversed() {
        projectionHandler.handleEvent(buildEvent("fund-1", "Fund", 1, "ALLOCATION_REQUESTED",
            Map.of("allocationId", "alloc-1", "requestedAmount", 500)));

        projectionHandler.handleEvent(buildEvent("fund-1", "Fund", 2, "ALLOCATION_REVERSED",
            Map.of("allocationId", "alloc-1")));

        Optional<PendingAllocationDocument> docOpt = repository.findById("alloc-1");
        assertTrue(docOpt.isPresent());
        assertEquals(AllocationStatus.REVERSED, docOpt.get().getStatus());
    }

    @Test
    void test4_Idempotency_Reprocessing() {
        projectionHandler.handleEvent(buildEvent("fund-1", "Fund", 1, "ALLOCATION_REQUESTED",
            Map.of("allocationId", "alloc-1", "requestedAmount", 500)));

        projectionHandler.handleEvent(buildEvent("fund-1", "Fund", 2, "ALLOCATION_CONFIRMED",
            Map.of("allocationId", "alloc-1")));

        // Reprocess REQUESTED -> should be ignored, status remains CONFIRMED
        projectionHandler.handleEvent(buildEvent("fund-1", "Fund", 1, "ALLOCATION_REQUESTED",
            Map.of("allocationId", "alloc-1", "requestedAmount", 500)));

        Optional<PendingAllocationDocument> docOpt = repository.findById("alloc-1");
        assertTrue(docOpt.isPresent());
        assertEquals(AllocationStatus.CONFIRMED, docOpt.get().getStatus());
    }

    @Test
    void test5_OrphanConfirmed_ThrowsException() {
        TraceabilityEventDocument event = buildEvent("fund-1", "Fund", 1, "ALLOCATION_CONFIRMED",
            Map.of("allocationId", "alloc-orphan"));

        assertThrows(DonationProjectionHandler.MissingDependencyException.class, () -> {
            projectionHandler.processEvent(event);
        });
    }

    @Test
    void test6_OrphanReversed_ThrowsException() {
        TraceabilityEventDocument event = buildEvent("fund-1", "Fund", 1, "ALLOCATION_REVERSED",
            Map.of("allocationId", "alloc-orphan"));

        assertThrows(DonationProjectionHandler.MissingDependencyException.class, () -> {
            projectionHandler.processEvent(event);
        });
    }

    @Test
    void test7_RealIntegration_EventSource() throws InterruptedException {
        // seq 1: REQUESTED(A)
        mongoTemplate.insert(buildEvent("fund-int", "Fund", 1, "ALLOCATION_REQUESTED",
            Map.of("allocationId", "alloc-int", "requestedAmount", 500)), "event_store");

        // seq 2: evento no relacionado
        mongoTemplate.insert(buildEvent("fund-int", "Fund", 2, "ALLOCATION_REQUESTED",
            Map.of("allocationId", "alloc-int-2", "requestedAmount", 300)), "event_store");

        // seq 3: CONFIRMED(A)
        mongoTemplate.insert(buildEvent("fund-int", "Fund", 3, "ALLOCATION_CONFIRMED",
            Map.of("allocationId", "alloc-int")), "event_store");

        // Esperar a que el motor real de Change Streams procese asincronamente
        Thread.sleep(3000);

        Optional<PendingAllocationDocument> docOptA = repository.findById("alloc-int");
        assertTrue(docOptA.isPresent(), "El documento alloc-int debio crearse a traves de Change Streams");
        assertEquals(AllocationStatus.CONFIRMED, docOptA.get().getStatus());

        Optional<PendingAllocationDocument> docOptB = repository.findById("alloc-int-2");
        assertTrue(docOptB.isPresent());
        assertEquals(AllocationStatus.REQUESTED, docOptB.get().getStatus());
    }
}
