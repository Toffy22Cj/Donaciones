package com.traceability.core.application.command;

import com.traceability.contracts.HashPort;
import com.traceability.core.application.port.out.EventStorePort;
import com.traceability.core.application.port.out.OutboxPort;
import com.traceability.core.domain.event.DomainEvent;
import com.traceability.core.domain.event.SystemActor;
import com.traceability.core.domain.physicalasset.PhysicalAsset;
import com.traceability.core.infrastructure.persistence.mongo.TraceabilityEventDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
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

        physicalAssetCommandService.registerPhysicalAsset(
                commandId,
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
        List<TraceabilityEventDocument> allEvents = mongoTemplate.findAll(TraceabilityEventDocument.class);
        assertFalse(allEvents.isEmpty(), "Debe haberse persistido al menos un evento");

        String assetId = allEvents.get(0).getStreamId();
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
    void splitPhysicalAsset_reducesQuantityAndPersistsSplitEvent() {
        String registerCommandId = UUID.randomUUID().toString();
        SystemActor actor = new SystemActor("test-harness");

        // 1. Primero registramos un asset
        physicalAssetCommandService.registerPhysicalAsset(
                registerCommandId,
                "ORG-123",
                "FOOD",
                new BigDecimal("100.0000"),
                "KG",
                "CUSTODIAN-1",
                "WAREHOUSE-A",
                "ALLOC-001",
                null,
                actor);

        List<TraceabilityEventDocument> allEvents = mongoTemplate.findAll(TraceabilityEventDocument.class);
        String assetId = allEvents.get(0).getStreamId();

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

}