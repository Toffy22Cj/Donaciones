package com.traceability.core.application.command;

import com.traceability.contracts.HashPort;
import com.traceability.core.application.port.out.EventStorePort;
import com.traceability.core.application.port.out.OutboxPort;
import com.traceability.core.domain.event.DomainEvent;
import com.traceability.core.domain.event.SystemActor;
import com.traceability.core.domain.fund.Fund;
import com.traceability.core.domain.fund.OrganizationRef;
import com.traceability.core.domain.fund.payloads.FundsClearedV2Payload;
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

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
    "core.projection.retry.delay=100",
    "core.projection.retry.timeout-minutes=5"
})
@Testcontainers
class FundCommandServiceClearFundsForPledgeIntegrationTest {

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

    // Helpers
    private String createFund(String orgId, String campaign, String donor, String currency, long pledgedAmount) {
        String fundId = UUID.randomUUID().toString();
        fundCommandService.registerFund(
                UUID.randomUUID().toString(),
                fundId,
                new OrganizationRef(orgId),
                campaign,
                donor,
                currency,
                pledgedAmount,
                new SystemActor("test")
        );
        return fundId;
    }

    @Test
    void clearFundsForPledge_sobreFundExistente() {
        String fundId = createFund("ORG-123", "CAMP-1", "DONOR-1", "COP", 1000L);
        String commandId = UUID.randomUUID().toString();

        fundCommandService.clearFundsForPledge(
                commandId,
                fundId,
                1000L,
                "TX-123",
                new SystemActor("test")
        );

        List<DomainEvent> stream = eventStorePort.loadStream(fundId);
        assertEquals(2, stream.size()); // REGISTERED, then CLEARED

        DomainEvent clearedEvent = stream.get(1);
        assertEquals("FUNDS_CLEARED", clearedEvent.eventType().name());

        List<TraceabilityEventDocument> docs = mongoTemplate.findAll(TraceabilityEventDocument.class);
        TraceabilityEventDocument clearedDoc = docs.stream()
                .filter(d -> d.getEventType().equals("FUNDS_CLEARED"))
                .findFirst()
                .orElseThrow();

        assertTrue(clearedDoc.getSequence() > 0);
        assertEquals(2, clearedDoc.getSequence());
    }

    @Test
    void payloadHeredaDatosDelFund() {
        String fundId = createFund("ORG-123", "CAMP-1", "DONOR-1", "COP", 1000L);
        String commandId = UUID.randomUUID().toString();

        fundCommandService.clearFundsForPledge(
                commandId,
                fundId,
                1000L,
                "TX-123",
                new SystemActor("test")
        );

        List<DomainEvent> stream = eventStorePort.loadStream(fundId);
        DomainEvent clearedEvent = stream.get(1);

        assertTrue(clearedEvent.payload() instanceof FundsClearedV2Payload);
        FundsClearedV2Payload payload = (FundsClearedV2Payload) clearedEvent.payload();

        assertEquals("ORG-123", payload.organizationRef());
        assertEquals("CAMP-1", payload.campaignRef());
        assertEquals("DONOR-1", payload.donorRef());
        assertEquals("COP", payload.currency());
        assertEquals(1000L, payload.clearedAmount());
        assertEquals("TX-123", payload.sourceReference());
    }

    @Test
    void mismoCommandIdEsIdempotente() {
        String fundId = createFund("ORG-123", "CAMP-1", "DONOR-1", "COP", 1000L);
        String commandId = UUID.randomUUID().toString();

        fundCommandService.clearFundsForPledge(commandId, fundId, 500L, "TX-1", new SystemActor("test"));
        fundCommandService.clearFundsForPledge(commandId, fundId, 500L, "TX-1", new SystemActor("test"));

        List<DomainEvent> stream = eventStorePort.loadStream(fundId);
        assertEquals(2, stream.size()); // REGISTERED, CLEARED (only once)

        Fund fund = Fund.rehydrate(fundId, stream.stream().map(DomainEvent::payload).toList(), stream.size());
        assertEquals(500L, fund.getClearedAmount());
    }

    @Test
    void amountInvalidoFalla() {
        String fundId = createFund("ORG-123", "CAMP-1", "DONOR-1", "COP", 1000L);
        String commandId = UUID.randomUUID().toString();

        assertThrows(IllegalArgumentException.class, () -> {
            fundCommandService.clearFundsForPledge(commandId, fundId, 0L, "TX-0", new SystemActor("test"));
        });

        assertThrows(IllegalArgumentException.class, () -> {
            fundCommandService.clearFundsForPledge(commandId, fundId, -100L, "TX-N", new SystemActor("test"));
        });
    }

    @Test
    void actorRefSePropaga() {
        String fundId = createFund("ORG-123", "CAMP-1", "DONOR-1", "COP", 1000L);
        String commandId = UUID.randomUUID().toString();
        SystemActor uniqueActor = new SystemActor("actor-propagated-123");

        fundCommandService.clearFundsForPledge(
                commandId,
                fundId,
                1000L,
                "TX-123",
                uniqueActor
        );

        List<TraceabilityEventDocument> docs = mongoTemplate.findAll(TraceabilityEventDocument.class);
        TraceabilityEventDocument clearedDoc = docs.stream()
                .filter(d -> d.getEventType().equals("FUNDS_CLEARED"))
                .findFirst()
                .orElseThrow();

        assertNotNull(clearedDoc.getActorRef());
        assertTrue(clearedDoc.getActorRef() instanceof SystemActor);
        assertEquals("actor-propagated-123", ((SystemActor)clearedDoc.getActorRef()).policyName());
    }
}
