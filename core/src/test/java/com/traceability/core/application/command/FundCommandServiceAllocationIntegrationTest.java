package com.traceability.core.application.command;

import com.traceability.contracts.HashPort;
import com.traceability.core.application.port.out.EventStorePort;
import com.traceability.core.application.port.out.OutboxPort;
import com.traceability.core.domain.event.DomainEvent;
import com.traceability.core.domain.event.SystemActor;
import com.traceability.core.domain.fund.Fund;
import com.traceability.core.domain.fund.OrganizationRef;
import com.traceability.core.domain.fund.exceptions.DuplicateAllocationException;
import com.traceability.core.domain.fund.exceptions.FundNotAssociatedToOrganizationException;
import com.traceability.core.domain.fund.exceptions.InsufficientAvailableFundsException;
import com.traceability.core.domain.fund.payloads.AllocationRequestedPayload;
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

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
    "core.projection.retry.delay=100",
    "core.projection.retry.timeout-minutes=5"
})
@Testcontainers
class FundCommandServiceAllocationIntegrationTest {

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
    static class TestConfig {}

    @Autowired
    private FundCommandService fundCommandService;

    @Autowired
    private EventStorePort eventStorePort;

    @Autowired
    private MongoTemplate mongoTemplate;

    private static final OrganizationRef ORG_REF = new OrganizationRef("ORG-TEST");
    private static final SystemActor ACTOR = new SystemActor("test-harness");

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

    // --- Helper: creates a Fund with cleared funds via genesis ---
    private String createFundWithClearedAmount(long amount) {
        String fundId = UUID.randomUUID().toString();
        String genesisCommandId = UUID.randomUUID().toString();
        fundCommandService.clearFundsGenesis(
                genesisCommandId, fundId, ORG_REF,
                "CAMP-1", "DONOR-1", "COP",
                amount, "TX-GENESIS", ACTOR
        );
        return fundId;
    }

    @Test
    void requestAllocation_happyPath_persistsEventAndUpdatesState() {
        String fundId = createFundWithClearedAmount(1000L);
        String commandId = UUID.randomUUID().toString();
        String allocationId = "ALLOC-" + UUID.randomUUID();

        fundCommandService.requestAllocation(commandId, fundId, allocationId, 400L, ACTOR);

        // Verify event stream
        List<DomainEvent> stream = eventStorePort.loadStream(fundId);
        assertEquals(2, stream.size()); // genesis + allocation
        DomainEvent allocationEvent = stream.get(1);
        assertEquals("ALLOCATION_REQUESTED", allocationEvent.eventType().name());

        // Verify payload
        assertInstanceOf(AllocationRequestedPayload.class, allocationEvent.payload());
        AllocationRequestedPayload payload = (AllocationRequestedPayload) allocationEvent.payload();
        assertEquals(allocationId, payload.allocationId());
        assertEquals(400L, payload.requestedAmount());

        // Verify reconstituted state
        Fund reconstituted = Fund.rehydrate(fundId, stream.stream().map(DomainEvent::payload).toList(), stream.size());
        assertEquals(400L, reconstituted.getPendingAllocationAmount());
        assertEquals(600L, reconstituted.getAvailableAmount());
    }

    @Test
    void requestAllocation_duplicateCommandId_isIdempotentNoOp() {
        String fundId = createFundWithClearedAmount(1000L);
        String commandId = UUID.randomUUID().toString();

        fundCommandService.requestAllocation(commandId, fundId, "ALLOC-1", 200L, ACTOR);
        fundCommandService.requestAllocation(commandId, fundId, "ALLOC-1", 200L, ACTOR);

        List<DomainEvent> stream = eventStorePort.loadStream(fundId);
        assertEquals(2, stream.size()); // genesis + single allocation, no duplicate
    }

    @Test
    void requestAllocation_duplicateAllocationId_throwsDuplicateAllocationException() {
        String fundId = createFundWithClearedAmount(1000L);

        fundCommandService.requestAllocation(UUID.randomUUID().toString(), fundId, "ALLOC-DUP", 100L, ACTOR);

        assertThrows(DuplicateAllocationException.class, () ->
                fundCommandService.requestAllocation(UUID.randomUUID().toString(), fundId, "ALLOC-DUP", 100L, ACTOR)
        );
    }

    @Test
    void requestAllocation_fundWithNoStream_throwsDomainException() {
        String nonExistentFundId = UUID.randomUUID().toString();

        // Fund rehydrated from empty stream has no organizationRef → FundNotAssociatedToOrganizationException
        assertThrows(FundNotAssociatedToOrganizationException.class, () ->
                fundCommandService.requestAllocation(UUID.randomUUID().toString(), nonExistentFundId, "ALLOC-1", 100L, ACTOR)
        );
    }

    @Test
    void requestAllocation_insufficientFunds_throwsInsufficientAvailableFundsException() {
        String fundId = createFundWithClearedAmount(100L);

        assertThrows(InsufficientAvailableFundsException.class, () ->
                fundCommandService.requestAllocation(UUID.randomUUID().toString(), fundId, "ALLOC-1", 150L, ACTOR)
        );
    }

    @Test
    void requestAllocation_eventTypeAndSequenceAreCorrect() {
        String fundId = createFundWithClearedAmount(500L);

        fundCommandService.requestAllocation(UUID.randomUUID().toString(), fundId, "ALLOC-SEQ", 200L, ACTOR);

        List<DomainEvent> stream = eventStorePort.loadStream(fundId);
        assertEquals(2, stream.size());

        // First event = genesis (FUNDS_CLEARED), second = ALLOCATION_REQUESTED
        assertEquals("FUNDS_CLEARED", stream.get(0).eventType().name());
        assertEquals("ALLOCATION_REQUESTED", stream.get(1).eventType().name());
    }

    @Test
    void requestAllocation_actorRefIsPropagated() {
        String fundId = createFundWithClearedAmount(500L);
        SystemActor specificActor = new SystemActor("allocation-service");

        // Should not throw — actor is passed through to appendAndOutbox
        assertDoesNotThrow(() ->
                fundCommandService.requestAllocation(UUID.randomUUID().toString(), fundId, "ALLOC-ACTOR", 100L, specificActor)
        );

        List<DomainEvent> stream = eventStorePort.loadStream(fundId);
        assertEquals(2, stream.size());
        assertEquals("ALLOCATION_REQUESTED", stream.get(1).eventType().name());
    }
}

