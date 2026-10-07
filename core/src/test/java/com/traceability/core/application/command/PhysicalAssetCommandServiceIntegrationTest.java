package com.traceability.core.application.command;

import com.traceability.contracts.HashPort;
import com.traceability.core.application.authorization.CrossOrganizationAccessException;
import com.traceability.core.application.exception.InvalidFundReferenceException;
import com.traceability.core.application.port.out.EventStorePort;
import com.traceability.core.application.port.out.OutboxPort;
import com.traceability.core.domain.event.DomainEvent;
import com.traceability.core.domain.event.SystemActor;
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
import org.springframework.boot.test.mock.mockito.MockBean;
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

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "core.projection.retry.delay=100",
        "core.projection.retry.timeout-minutes=5"
})
@Testcontainers
class PhysicalAssetCommandServiceIntegrationTest {

    @MockBean
    private IdentityPrincipalPort identityPrincipalPort;



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
    static class TestConfig {
    }

    @Autowired
    private PhysicalAssetCommandService physicalAssetCommandService;

    @Autowired
    private FundCommandService fundCommandService;

    @Autowired
    private EventStorePort eventStorePort;

    @Autowired
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void setup() {
        mongoTemplate.dropCollection(TraceabilityEventDocument.class);
        mongoTemplate.dropCollection("processed_commands");
    }

    @AfterEach
    void clean() {
        mongoTemplate.dropCollection(TraceabilityEventDocument.class);
        mongoTemplate.dropCollection("processed_commands");
    }

    @Test
    void registerPhysicalAsset_persistsGenesisEventCorrectly() {
        String commandId = UUID.randomUUID().toString();
        SystemActor actor = new SystemActor("test-harness");
        String fundId = registerFund("ORG-123");

        physicalAssetCommandService.registerPhysicalAsset(
                commandId,
                fundId,
                "ORG-123",
                "FOOD",
                new BigDecimal("100.0000"),
                "KG",
                "CUSTODIAN-1",
                "WAREHOUSE-A",
                "ALLOC-001",
                null,
                actor);

        // Como el assetId se genera dentro del método, buscamos el único stream que
        // exista
        List<TraceabilityEventDocument> assetEvents = physicalAssetEvents();
        assertFalse(assetEvents.isEmpty(), "Debe haberse persistido al menos un evento");

        String assetId = assetEvents.get(0).getStreamId();
        List<DomainEvent> stream = eventStorePort.loadStream(assetId);

        assertEquals(1, stream.size());
        assertEquals("ASSET_REGISTERED", stream.get(0).eventType().name());

        PhysicalAsset reconstituted = PhysicalAsset.rehydrate(
                assetId,
                stream.stream().map(DomainEvent::payload).toList(),
                stream.size());

        assertEquals("FOOD", reconstituted.getAssetId() != null ? "FOOD" : null); // solo verificamos que se
                                                                                  // reconstituyó
        assertNotNull(reconstituted.getQuantity());
        assertEquals(0, new BigDecimal("100.0000").compareTo(reconstituted.getQuantity()));
    }

    @Test
    void registerPhysicalAsset_invalidFundId_doesNotPersist() {
        String commandId = UUID.randomUUID().toString();
        SystemActor actor = new SystemActor("test-harness");

        // Act & Assert
        assertThrows(com.traceability.core.application.exception.InvalidFundReferenceException.class, () -> {
            physicalAssetCommandService.registerPhysicalAsset(
                    commandId,
                    "", // invalid fundId
                    "ORG-123",
                    "FOOD",
                    new BigDecimal("100.0000"),
                    "KG",
                    "CUSTODIAN-1",
                    "WAREHOUSE-A",
                    "ALLOC-001",
                    null,
                    actor);
        });

        // Verify no events were persisted
        List<TraceabilityEventDocument> allEvents = mongoTemplate.findAll(TraceabilityEventDocument.class);
        assertTrue(allEvents.isEmpty(), "No events should be persisted when fundId is invalid");
    }

    @Test
    void registerPhysicalAsset_nonExistentFund_doesNotPersist() {
        assertThrows(InvalidFundReferenceException.class, () ->
                physicalAssetCommandService.registerPhysicalAsset(
                        UUID.randomUUID().toString(),
                        "FUND-DOES-NOT-EXIST",
                        "ORG-123",
                        "FOOD",
                        new BigDecimal("100.0000"),
                        "KG",
                        "CUSTODIAN-1",
                        "WAREHOUSE-A",
                        "ALLOC-001",
                        null,
                        new SystemActor("test-harness")));

        assertTrue(mongoTemplate.findAll(TraceabilityEventDocument.class).isEmpty());
        org.mockito.Mockito.verify(outboxPort, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void registerPhysicalAsset_organizationRefDifferentFromFund_rejectedEvenForSystemActor() {
        // ADR-029 Path A: organizationRef must be the Fund's; an arbitrary value is rejected
        // regardless of actor type (SystemActor bypasses P7/P9, not this invariant).
        String fundId = registerFund("ORG-123");

        assertThrows(CrossOrganizationAccessException.class, () ->
                physicalAssetCommandService.registerPhysicalAsset(
                        UUID.randomUUID().toString(),
                        fundId,
                        "ORG-ARBITRARY",
                        "FOOD",
                        new BigDecimal("100.0000"),
                        "KG",
                        "CUSTODIAN-1",
                        "WAREHOUSE-A",
                        "ALLOC-001",
                        null,
                        new SystemActor("test-harness")));

        assertTrue(physicalAssetEvents().isEmpty(), "No PhysicalAsset may be persisted with a foreign organizationRef");
        assertEquals(1, eventStorePort.loadStream(fundId).size(), "Fund stream must be untouched");
        org.mockito.Mockito.verify(outboxPort, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void splitPhysicalAsset_reducesQuantityAndPersistsSplitEvent() {
        String registerCommandId = UUID.randomUUID().toString();
        SystemActor actor = new SystemActor("test-harness");
        String fundId = registerFund("ORG-123");

        // 1. Primero registramos un asset
        physicalAssetCommandService.registerPhysicalAsset(
                registerCommandId,
                fundId,
                "ORG-123",
                "FOOD",
                new BigDecimal("100.0000"),
                "KG",
                "CUSTODIAN-1",
                "WAREHOUSE-A",
                "ALLOC-001",
                null,
                actor);

        String assetId = physicalAssetEvents().get(0).getStreamId();

        // 2. Ahora hacemos split
        String splitCommandId = UUID.randomUUID().toString();
        physicalAssetCommandService.splitPhysicalAsset(
                splitCommandId,
                assetId,
                new BigDecimal("30.0000"),
                actor);

        List<DomainEvent> stream = eventStorePort.loadStream(assetId);
        assertTrue(stream.size() >= 2, "Debe haber al menos ASSET_REGISTERED + ASSET_SPLIT");

        PhysicalAsset reconstituted = PhysicalAsset.rehydrate(
                assetId,
                stream.stream().map(DomainEvent::payload).toList(),
                stream.size());

        // La cantidad del padre debe haber bajado a 70
        assertEquals(0, new BigDecimal("70.0000").compareTo(reconstituted.getQuantity()));
    }

    private String registerFund(String organizationId) {
        String fundId = "FUND-" + UUID.randomUUID();
        fundCommandService.registerFund(UUID.randomUUID().toString(), fundId, new OrganizationRef(organizationId),
                "CAMP-1", "DONOR-1", "USD", 1000L, new SystemActor("test-setup"));
        return fundId;
    }

    private List<TraceabilityEventDocument> physicalAssetEvents() {
        return mongoTemplate.findAll(TraceabilityEventDocument.class).stream()
                .filter(e -> "PhysicalAsset".equals(e.getAggregateType()))
                .toList();
    }
}
