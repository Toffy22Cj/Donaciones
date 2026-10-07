package com.traceability.core.application.command;

import com.traceability.core.application.authorization.OrganizationBoundaryPolicy;
import com.traceability.core.application.authorization.RoleAuthorizationPolicy;
import com.traceability.core.application.port.out.EventStorePort;
import com.traceability.core.application.port.out.OutboxPort;
import com.traceability.core.application.saga.OutboxMessage;
import com.traceability.core.application.saga.OutboxSagaCoordinator;
import com.traceability.core.domain.event.SystemActor;
import com.traceability.core.domain.fund.Fund;
import com.traceability.core.domain.fund.OrganizationRef;
import com.traceability.core.domain.physicalasset.PhysicalAsset;
import com.traceability.core.infrastructure.persistence.mongo.TraceabilityEventDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.mock.mockito.MockBean;
import com.traceability.contracts.authorization.IdentityPrincipalPort;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = {
    "core.projection.retry.delay=100",
    "core.projection.retry.timeout-minutes=5"
})
@Testcontainers
class Phase5EndToEndIntegrationTest {

    @MockBean
    private IdentityPrincipalPort identityPrincipalPort;



    @Container
    static MongoDBContainer mongoDBContainer = new MongoDBContainer(DockerImageName.parse("mongo:6.0.4"));

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongoDBContainer::getReplicaSetUrl);
    }

    @Configuration
    @SpringBootApplication(scanBasePackages = "com.traceability.core")
    @org.springframework.data.mongodb.repository.config.EnableMongoRepositories(basePackages = "com.traceability.core.infrastructure")
    static class TestConfig {
    }

    @Autowired
    private FundCommandService fundCommandService;

    @Autowired
    private PhysicalAssetCommandService physicalAssetCommandService;

    @Autowired
    private EventStorePort eventStorePort;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private OutboxSagaCoordinator outboxSagaCoordinator;

    @Autowired
    private OutboxPort outboxPort;

    @SpyBean
    private RoleAuthorizationPolicy roleAuthorizationPolicy;

    @SpyBean
    private OrganizationBoundaryPolicy organizationBoundaryPolicy;

    @org.springframework.boot.test.mock.mockito.MockBean
    private com.traceability.contracts.HashPort hashPort;

    private static final SystemActor ACTOR = new SystemActor("integration-tests");

    @BeforeEach
    void setup() {
        mongoTemplate.dropCollection(TraceabilityEventDocument.class);
        mongoTemplate.dropCollection("processed_commands");
        mongoTemplate.dropCollection("outbox");
    }

    @AfterEach
    void clean() {
        mongoTemplate.dropCollection(TraceabilityEventDocument.class);
        mongoTemplate.dropCollection("processed_commands");
        mongoTemplate.dropCollection("outbox");
    }

    @Test
    void testA_registerFund_withBypass() {
        String fundId = "FUND-" + UUID.randomUUID();
        String commandId = UUID.randomUUID().toString();
        OrganizationRef orgRef = new OrganizationRef("ORG-1");

        fundCommandService.registerFund(commandId, fundId, orgRef, "CAMP-1", "DON-1", "USD", 1000L, ACTOR);

        List<com.traceability.core.domain.event.DomainEvent> events = eventStorePort.loadStream(fundId);
        List<com.traceability.core.domain.event.DomainEventPayload> payloads = events.stream().map(com.traceability.core.domain.event.DomainEvent::payload).toList();
        Fund fund = Fund.rehydrate(fundId, payloads, events.size());
        assertNotNull(fund);
        assertEquals("ORG-1", fund.getOrganizationRef().value());

        verify(roleAuthorizationPolicy, never()).authorize(any(), any());
        verify(organizationBoundaryPolicy, never()).assertBelongs(any(), any());
    }

    @Test
    void testB_clearFundsGenesis_withBypass() {
        String fundId = "FUND-" + UUID.randomUUID();
        String commandId = UUID.randomUUID().toString();
        OrganizationRef orgRef = new OrganizationRef("ORG-1");

        fundCommandService.clearFundsGenesis(commandId, fundId, orgRef, "CAMP-1", "DON-1", "USD", 1000L, "SRC-1", ACTOR);

        List<com.traceability.core.domain.event.DomainEvent> events = eventStorePort.loadStream(fundId);
        List<com.traceability.core.domain.event.DomainEventPayload> payloads = events.stream().map(com.traceability.core.domain.event.DomainEvent::payload).toList();
        Fund fund = Fund.rehydrate(fundId, payloads, events.size());
        assertNotNull(fund);
        assertEquals(1000L, fund.getClearedAmount());
        assertEquals("ORG-1", fund.getOrganizationRef().value());

        verify(roleAuthorizationPolicy, never()).authorize(any(), any());
        verify(organizationBoundaryPolicy, never()).assertBelongs(any(), any());
    }

    @Test
    void testC_clearFundsForPledge_withBypass() {
        String fundId = "FUND-" + UUID.randomUUID();
        OrganizationRef orgRef = new OrganizationRef("ORG-1");
        fundCommandService.registerFund(UUID.randomUUID().toString(), fundId, orgRef, "CAMP-1", "DON-1", "USD", 1000L, ACTOR);

        fundCommandService.clearFundsForPledge(UUID.randomUUID().toString(), fundId, 1000L, "SRC-1", ACTOR);

        List<com.traceability.core.domain.event.DomainEvent> events = eventStorePort.loadStream(fundId);
        List<com.traceability.core.domain.event.DomainEventPayload> payloads = events.stream().map(com.traceability.core.domain.event.DomainEvent::payload).toList();
        Fund fund = Fund.rehydrate(fundId, payloads, events.size());
        assertNotNull(fund);
        assertEquals(1000L, fund.getClearedAmount());

        verify(roleAuthorizationPolicy, never()).authorize(any(), any());
        verify(organizationBoundaryPolicy, never()).assertBelongs(any(), any());
    }

    @Test
    void testD1_caminoA_registerPhysicalAsset_inheritsFundOrganization_andEmitsSagaEnvelope() {
        String fundId = "FUND-" + UUID.randomUUID();
        OrganizationRef orgRef = new OrganizationRef("ORG-1");
        
        fundCommandService.clearFundsGenesis(UUID.randomUUID().toString(), fundId, orgRef, "CAMP-1", "DONOR-1", "USD", 1000L, "SRC", ACTOR);
        
        String allocationId = "ALLOC-1";
        fundCommandService.requestAllocation(UUID.randomUUID().toString(), fundId, allocationId, 500L, ACTOR);
        
        String commandId = UUID.randomUUID().toString();
        physicalAssetCommandService.registerPhysicalAsset(
            commandId,
            fundId,
            orgRef.value(),
            "TYPE-1",
            BigDecimal.valueOf(10),
            "KG",
            "CUST-1",
            "LOC-1",
            allocationId,
            null,
            ACTOR
        );
        
        List<TraceabilityEventDocument> tempEvents = mongoTemplate.findAll(TraceabilityEventDocument.class);
        String theAssetId = tempEvents.stream()
                .filter(e -> "PhysicalAsset".equals(e.getAggregateType()))
                .map(TraceabilityEventDocument::getStreamId)
                .findFirst()
                .orElseThrow();

        List<com.traceability.core.domain.event.DomainEvent> assetEvents = eventStorePort.loadStream(theAssetId);
        List<com.traceability.core.domain.event.DomainEventPayload> payloads = assetEvents.stream().map(com.traceability.core.domain.event.DomainEvent::payload).toList();
        PhysicalAsset asset = PhysicalAsset.rehydrate(theAssetId, payloads, assetEvents.size());

        List<com.traceability.core.domain.event.DomainEvent> fundEvents = eventStorePort.loadStream(fundId);
        Fund fund = Fund.rehydrate(fundId, fundEvents.stream().map(com.traceability.core.domain.event.DomainEvent::payload).toList(), fundEvents.size());

        assertNotNull(asset);
        assertEquals(fund.getOrganizationRef().value(), asset.getOrganizationRef()); // ADR-029: Fund's organization
        assertNull(asset.getDonorRef()); // Camino A donorRef null
        assertEquals(allocationId, org.springframework.test.util.ReflectionTestUtils.getField(asset, "allocationId"));

        // ASSET_REGISTRATION_SAGA envelope (ADR-033) points at the real Fund, not at the commandId
        List<OutboxMessage> sagaMessages = outboxPort.fetchPendingMessages(java.time.Instant.now().plusSeconds(60)).stream()
                .filter(m -> theAssetId.equals(m.sourceAggregateId()))
                .toList();
        assertEquals(1, sagaMessages.size());
        OutboxMessage sagaMessage = sagaMessages.get(0);
        assertEquals("ASSET_REGISTRATION_SAGA", sagaMessage.sagaType());
        assertEquals(fundId, sagaMessage.correlationId());
        assertEquals("{\"allocationId\":\"" + allocationId + "\",\"fundId\":\"" + fundId + "\"}", sagaMessage.payload());
        org.junit.jupiter.api.Assertions.assertNotEquals(commandId, sagaMessage.messageId());

        verify(roleAuthorizationPolicy, never()).authorize(any(), any());
        verify(organizationBoundaryPolicy, never()).assertBelongs(any(), any());
    }

    @Test
    void testD2_isolated_AssetRegisteredSagaPolicy_confirmAllocation() {
        String fundId = "FUND-" + UUID.randomUUID();
        OrganizationRef orgRef = new OrganizationRef("ORG-1");

        fundCommandService.clearFundsGenesis(UUID.randomUUID().toString(), fundId, orgRef, "CAMP-1", "DONOR-1", "USD", 1000L, "SRC", ACTOR);

        String allocationId = "ALLOC-2";
        fundCommandService.requestAllocation(UUID.randomUUID().toString(), fundId, allocationId, 500L, ACTOR);

        // Act: Call registerPhysicalAsset organically which should generate the OutboxMessage
        physicalAssetCommandService.registerPhysicalAsset(
                UUID.randomUUID().toString(),
                fundId,
                "ORG-1",
                "VACCINE",
                BigDecimal.valueOf(100),
                "DOSES",
                "CUST-1",
                "LOC-1",
                allocationId,
                null,
                ACTOR
        );

        // Process outbox to trigger Saga
        outboxSagaCoordinator.processPendingMessages();

        List<com.traceability.core.domain.event.DomainEvent> eventsF = eventStorePort.loadStream(fundId);
        List<com.traceability.core.domain.event.DomainEventPayload> payloadsF = eventsF.stream().map(com.traceability.core.domain.event.DomainEvent::payload).toList();
        Fund fund = Fund.rehydrate(fundId, payloadsF, eventsF.size());
        assertEquals(500L, fund.getAllocatedAmount());
    }

    @Test
    void testE_splitPhysicalAsset_withBypass() {
        String orgRefValue = "ORG-1";
        String fundId = "FUND-" + UUID.randomUUID();
        fundCommandService.clearFundsGenesis(UUID.randomUUID().toString(), fundId, new OrganizationRef(orgRefValue), "CAMP-1", "DONOR-1", "USD", 1000L, "SRC", ACTOR);

        String commandId = UUID.randomUUID().toString();
        physicalAssetCommandService.registerPhysicalAsset(
            commandId,
            fundId,
            orgRefValue,
            "TYPE-1",
            BigDecimal.valueOf(100),
            "KG",
            "CUST-1",
            "LOC-1",
            "ALLOC-1",
            null,
            ACTOR
        );

        List<TraceabilityEventDocument> events = mongoTemplate.findAll(TraceabilityEventDocument.class);
        String parentAssetId = events.stream()
                .filter(e -> "PhysicalAsset".equals(e.getAggregateType()))
                .map(TraceabilityEventDocument::getStreamId)
                .findFirst()
                .orElseThrow();

        String splitCommandId = UUID.randomUUID().toString();
        physicalAssetCommandService.splitPhysicalAsset(
                splitCommandId,
                parentAssetId,
                BigDecimal.valueOf(20),
                ACTOR
        );

        List<TraceabilityEventDocument> updatedEvents = mongoTemplate.findAll(TraceabilityEventDocument.class);
        
        List<com.traceability.core.domain.event.DomainEvent> parentEvents = eventStorePort.loadStream(parentAssetId);
        List<com.traceability.core.domain.event.DomainEventPayload> parentPayloads = parentEvents.stream().map(com.traceability.core.domain.event.DomainEvent::payload).toList();
        PhysicalAsset parentAsset = PhysicalAsset.rehydrate(parentAssetId, parentPayloads, parentEvents.size());

        // NOTA DE ALCANCE (Tarea 5.10):
        // Este test de integración tiene alcance limitado. Únicamente demuestra que
        // la operación split decrementa el balance del agregado padre.
        // NO demuestra que el agregado hijo (child asset stream) sea creado, instanciado
        // o orquestado en Event Sourcing, ya que esto depende del alcance pendiente de la Tarea 5.5.
        assertEquals(0, parentAsset.getQuantity().compareTo(BigDecimal.valueOf(80)));
        verify(organizationBoundaryPolicy, never()).assertBelongs(any(), any());
    }
}
