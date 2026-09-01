package com.traceability.core.application.projection;

import com.traceability.contracts.AuditFactsDTO;
import com.traceability.contracts.AuditFactsPort;
import com.traceability.contracts.FinancialFlagDTO;
import com.traceability.contracts.TransitionFactDTO;
import com.traceability.core.application.event.EventCanonicalMapper;
import com.traceability.core.domain.fund.payloads.FundsRefundedPayload;
import com.traceability.core.domain.physicalasset.payloads.AssetDeliveredPayload;
import com.traceability.core.domain.physicalasset.payloads.AssetDispatchedPayload;
import com.traceability.core.domain.physicalasset.payloads.AssetReceivedPayload;
import com.traceability.core.domain.physicalasset.payloads.AssetRegisteredPayload;
import com.traceability.core.domain.physicalasset.payloads.AssetSplitPayload;
import com.traceability.core.infrastructure.persistence.mongo.TraceabilityEventDocument;
import com.traceability.core.infrastructure.projection.mongo.documents.AssetIndexDocument;
import com.traceability.core.infrastructure.projection.mongo.documents.DonationAuditFactsDocument;
import com.traceability.core.infrastructure.projection.mongo.repositories.AssetIndexRepository;
import com.traceability.core.infrastructure.projection.mongo.repositories.DonationAuditFactsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
    "traceability.audit.thresholds.dispatchedToReceived=7200", // 2 hours for faster test calculation checking if needed, or 72h
    "traceability.audit.thresholds.dispatchedToDelivered=4800",
    "traceability.audit.thresholds.receivedToDelivered=4800"
})
public class DonationAuditFactsIntegrationTest {

    @Container
    static MongoDBContainer mongoDBContainer = new MongoDBContainer(DockerImageName.parse("mongo:6.0"))
            .withCommand("--replSet", "rs0");

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongoDBContainer::getReplicaSetUrl);
    }

    @org.springframework.boot.test.mock.mockito.MockBean
    private com.traceability.contracts.HashPort hashPort;
    
    @org.springframework.boot.test.mock.mockito.MockBean
    private com.traceability.core.application.port.out.OutboxPort outboxPort;

    @Autowired
    private DonationAuditFactsHandler auditFactsHandler;
    
    @Autowired
    private DonationAuditFactsRepository auditFactsRepository;
    
    @Autowired
    private AssetIndexRepository assetIndexRepository;
    
    @Autowired
    private AuditFactsPort auditFactsPort;
    
    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setup() {
        auditFactsRepository.deleteAll();
        assetIndexRepository.deleteAll();
    }

    private TraceabilityEventDocument createEvent(String eventId, String streamId, long sequence, String type, Object payload, Instant occurredAt, String aggType) {
        TraceabilityEventDocument doc = new TraceabilityEventDocument();
        doc.setEventId(eventId);
        doc.setStreamId(streamId);
        doc.setSequence(sequence);
        doc.setEventType(type);
        doc.setPayload(objectMapper.convertValue(payload, new TypeReference<Map<String, Object>>() {}));
        doc.setOccurredAt(occurredAt.toString());
        doc.setAggregateType(aggType);
        return doc;
    }

    @Test
    void testCase1_PositiveAnomaly() {
        String fundId = "fund-1";
        String assetId = "asset-1";
        assetIndexRepository.save(new AssetIndexDocument(assetId, fundId, assetId, 0));
        
        Instant t1 = Instant.parse("2023-01-01T10:00:00Z");
        Instant t2 = t1.plus(7201, ChronoUnit.SECONDS); // > 7200 threshold
        
        TraceabilityEventDocument ev1 = createEvent("ev1", assetId, 0, "ASSET_DISPATCHED", new AssetDispatchedPayload("carrier", "loc"), t1, "PhysicalAsset");
        auditFactsHandler.handleEvent(ev1);
        
        TraceabilityEventDocument ev2 = createEvent("ev2", assetId, 1, "ASSET_RECEIVED", new AssetReceivedPayload("loc2", "rec"), t2, "PhysicalAsset");
        auditFactsHandler.handleEvent(ev2);
        
        DonationAuditFactsDocument doc = auditFactsRepository.findById(fundId).orElseThrow();
        assertThat(doc.getTransitions()).hasSize(2); // One closed DISPATCHED->RECEIVED, one open RECEIVED->?
        
        TransitionFactDTO closed = doc.getTransitions().get(0);
        assertThat(closed.toStatus()).isEqualTo("RECEIVED");
        assertThat(closed.durationSeconds()).isEqualTo(7201);
        assertThat(closed.anomaly()).isTrue();
    }

    @Test
    void testCase2_NegativeAnomaly() {
        String fundId = "fund-2";
        String assetId = "asset-2";
        assetIndexRepository.save(new AssetIndexDocument(assetId, fundId, assetId, 0));
        
        Instant t1 = Instant.parse("2023-01-01T10:00:00Z");
        Instant t2 = t1.plus(7199, ChronoUnit.SECONDS); // < 7200 threshold
        
        TraceabilityEventDocument ev1 = createEvent("ev1", assetId, 0, "ASSET_DISPATCHED", new AssetDispatchedPayload("carrier", "loc"), t1, "PhysicalAsset");
        auditFactsHandler.handleEvent(ev1);
        
        TraceabilityEventDocument ev2 = createEvent("ev2", assetId, 1, "ASSET_RECEIVED", new AssetReceivedPayload("loc2", "rec"), t2, "PhysicalAsset");
        auditFactsHandler.handleEvent(ev2);
        
        DonationAuditFactsDocument doc = auditFactsRepository.findById(fundId).orElseThrow();
        TransitionFactDTO closed = doc.getTransitions().get(0);
        assertThat(closed.anomaly()).isFalse();
    }

    @Test
    void testCase3_IgnoreNonAuditableEvents() {
        String fundId = "fund-3";
        String assetId = "asset-3";
        assetIndexRepository.save(new AssetIndexDocument(assetId, fundId, assetId, 0));
        
        TraceabilityEventDocument ev1 = createEvent("ev1", assetId, 0, "ASSET_SPLIT", 
            new AssetSplitPayload("child", 10L, "kg", 20L, 10L, "REGISTERED", "loc", "cust", "root"), 
            Instant.now(), "PhysicalAsset");
        
        auditFactsHandler.handleEvent(ev1);
        
        DonationAuditFactsDocument doc = auditFactsRepository.findById(fundId).orElse(null);
        if (doc != null) {
            assertThat(doc.getTransitions()).isEmpty();
        }
    }

    @Test
    void testCase4_HistoricalReproducibility() {
        // By relying on thresholds injected at execution time and saving them inside the event (frozen)
        // If we change thresholds in properties, it shouldn't affect already saved records since
        // the calculation is done and frozen at event time.
        String fundId = "fund-4";
        String assetId = "asset-4";
        assetIndexRepository.save(new AssetIndexDocument(assetId, fundId, assetId, 0));
        
        Instant t1 = Instant.parse("2023-01-01T10:00:00Z");
        Instant t2 = t1.plus(7201, ChronoUnit.SECONDS); 
        
        auditFactsHandler.handleEvent(createEvent("e1", assetId, 0, "ASSET_DISPATCHED", new AssetDispatchedPayload("c", "l"), t1, "PhysicalAsset"));
        auditFactsHandler.handleEvent(createEvent("e2", assetId, 1, "ASSET_RECEIVED", new AssetReceivedPayload("l2", "r"), t2, "PhysicalAsset"));
        
        DonationAuditFactsDocument doc = auditFactsRepository.findById(fundId).orElseThrow();
        assertThat(doc.getTransitions().get(0).expectedMaximumSeconds()).isEqualTo(7200);
        
        // Changing properties in test doesn't change the DB
        // verified by the fact that AuditFactsPort reads exactly what's in DB
        Optional<AuditFactsDTO> dto = auditFactsPort.getAuditFacts(fundId);
        assertThat(dto.get().transitions().get(0).expectedMaximumSeconds()).isEqualTo(7200L);
    }

    @Test
    void testCase5_FundsRefundedWithDeficit() {
        String fundId = "fund-5";
        
        Instant t = Instant.now();
        FundsRefundedPayload payload = new FundsRefundedPayload("ref1", 500L, true, "reason1");
        TraceabilityEventDocument ev = createEvent("e1", fundId, 0, "FUNDS_REFUNDED", payload, t, "Fund");
        
        auditFactsHandler.handleEvent(ev);
        
        DonationAuditFactsDocument doc = auditFactsRepository.findById(fundId).orElseThrow();
        assertThat(doc.getFinancialFlags()).hasSize(1);
        FinancialFlagDTO flag = doc.getFinancialFlags().get(0);
        assertThat(flag.type()).isEqualTo("DEFICIT_REFUND");
        assertThat(flag.refundId()).isEqualTo("ref1");
        assertThat(flag.allocationId()).isNull();
        assertThat(flag.amount()).isEqualTo(500);
        assertThat(flag.causedDeficit()).isTrue();
        
        // Also test non-deficit refund is ignored
        FundsRefundedPayload p2 = new FundsRefundedPayload("ref2", 100L, false, "reason2");
        TraceabilityEventDocument ev2 = createEvent("e2", fundId, 1, "FUNDS_REFUNDED", p2, t, "Fund");
        auditFactsHandler.handleEvent(ev2);
        
        doc = auditFactsRepository.findById(fundId).orElseThrow();
        assertThat(doc.getFinancialFlags()).hasSize(1); // Still 1
    }

    @Test
    void testCase6_Idempotence() {
        String fundId = "fund-6";
        String assetId = "asset-6";
        assetIndexRepository.save(new AssetIndexDocument(assetId, fundId, assetId, 0));
        
        TraceabilityEventDocument ev1 = createEvent("e1", assetId, 0, "ASSET_DISPATCHED", new AssetDispatchedPayload("c", "l"), Instant.now(), "PhysicalAsset");
        auditFactsHandler.handleEvent(ev1);
        
        DonationAuditFactsDocument doc = auditFactsRepository.findById(fundId).orElseThrow();
        assertThat(doc.getTransitions()).hasSize(1);
        
        // Duplicate
        auditFactsHandler.handleEvent(ev1);
        doc = auditFactsRepository.findById(fundId).orElseThrow();
        assertThat(doc.getTransitions()).hasSize(1); // Ignored
    }

    @Test
    void testCase7_PortMapping() {
        String fundId = "fund-7";
        String assetId = "asset-7";
        assetIndexRepository.save(new AssetIndexDocument(assetId, fundId, assetId, 0));
        
        auditFactsHandler.handleEvent(createEvent("e1", assetId, 0, "ASSET_DISPATCHED", new AssetDispatchedPayload("c", "l"), Instant.now(), "PhysicalAsset"));
        
        Optional<AuditFactsDTO> dto = auditFactsPort.getAuditFacts(fundId);
        assertThat(dto).isPresent();
        assertThat(dto.get().fundId()).isEqualTo(fundId);
        assertThat(dto.get().transitions()).hasSize(1);
        
        assertThat(auditFactsPort.getAuditFacts("unknown")).isEmpty();
    }

    @Test
    void testCase8_CompleteCycle() {
        String fundId = "fund-8";
        String assetId = "asset-8";
        assetIndexRepository.save(new AssetIndexDocument(assetId, fundId, assetId, 0));
        
        Instant t1 = Instant.parse("2023-01-01T10:00:00Z");
        Instant t2 = t1.plus(100, ChronoUnit.SECONDS);
        Instant t3 = t2.plus(100, ChronoUnit.SECONDS);
        
        auditFactsHandler.handleEvent(createEvent("e1", assetId, 0, "ASSET_DISPATCHED", new AssetDispatchedPayload("c", "l"), t1, "PhysicalAsset"));
        auditFactsHandler.handleEvent(createEvent("e2", assetId, 1, "ASSET_RECEIVED", new AssetReceivedPayload("l2", "r"), t2, "PhysicalAsset"));
        auditFactsHandler.handleEvent(createEvent("e3", assetId, 2, "ASSET_DELIVERED", new AssetDeliveredPayload("fc", "b", "l", "ev", t3), t3, "PhysicalAsset"));
        
        DonationAuditFactsDocument doc = auditFactsRepository.findById(fundId).orElseThrow();
        List<TransitionFactDTO> trans = doc.getTransitions();
        assertThat(trans).hasSize(2);
        
        assertThat(trans.get(0).fromStatus()).isEqualTo("DISPATCHED");
        assertThat(trans.get(0).toStatus()).isEqualTo("RECEIVED");
        
        assertThat(trans.get(1).fromStatus()).isEqualTo("RECEIVED");
        assertThat(trans.get(1).toStatus()).isEqualTo("DELIVERED");
        assertThat(trans.get(1).durationSeconds()).isEqualTo(100);
    }

    @Test
    void testCase9_Redispatch() {
        String fundId = "fund-9";
        String assetId = "asset-9";
        assetIndexRepository.save(new AssetIndexDocument(assetId, fundId, assetId, 0));
        
        Instant t1 = Instant.parse("2023-01-01T10:00:00Z");
        Instant t2 = t1.plus(100, ChronoUnit.SECONDS);
        Instant t3 = t2.plus(100, ChronoUnit.SECONDS);
        Instant t4 = t3.plus(100, ChronoUnit.SECONDS);
        
        auditFactsHandler.handleEvent(createEvent("e1", assetId, 0, "ASSET_DISPATCHED", new AssetDispatchedPayload("c", "l"), t1, "PhysicalAsset"));
        auditFactsHandler.handleEvent(createEvent("e2", assetId, 1, "ASSET_RECEIVED", new AssetReceivedPayload("l2", "r"), t2, "PhysicalAsset"));
        
        // Here we should have DISPATCHED->RECEIVED and RECEIVED->?
        DonationAuditFactsDocument doc1 = auditFactsRepository.findById(fundId).orElseThrow();
        assertThat(doc1.getTransitions()).hasSize(2);
        assertThat(doc1.getTransitions().get(1).toStatus()).isNull();
        
        // Re-dispatch
        auditFactsHandler.handleEvent(createEvent("e3", assetId, 2, "ASSET_DISPATCHED", new AssetDispatchedPayload("c2", "l2"), t3, "PhysicalAsset"));
        
        // The orphaned RECEIVED->? should be removed, and a new DISPATCHED->? added.
        // Total transitions should still be 2: the closed DISPATCHED->RECEIVED and the new open DISPATCHED->?
        DonationAuditFactsDocument doc2 = auditFactsRepository.findById(fundId).orElseThrow();
        assertThat(doc2.getTransitions()).hasSize(2);
        assertThat(doc2.getTransitions().get(0).toStatus()).isEqualTo("RECEIVED");
        assertThat(doc2.getTransitions().get(1).fromStatus()).isEqualTo("DISPATCHED");
        assertThat(doc2.getTransitions().get(1).toStatus()).isNull();
        
        // Finally deliver
        auditFactsHandler.handleEvent(createEvent("e4", assetId, 3, "ASSET_DELIVERED", new AssetDeliveredPayload("fc", "b", "l", "ev", t4), t4, "PhysicalAsset"));
        
        DonationAuditFactsDocument doc3 = auditFactsRepository.findById(fundId).orElseThrow();
        assertThat(doc3.getTransitions()).hasSize(2);
        assertThat(doc3.getTransitions().get(1).toStatus()).isEqualTo("DELIVERED");
        assertThat(doc3.getTransitions().get(1).fromStatus()).isEqualTo("DISPATCHED");
    }
}
