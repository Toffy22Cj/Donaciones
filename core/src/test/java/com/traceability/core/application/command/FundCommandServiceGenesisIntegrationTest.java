package com.traceability.core.application.command;

import com.traceability.contracts.HashPort;
import com.traceability.core.application.port.out.EventStorePort;
import com.traceability.core.application.port.out.OutboxPort;
import com.traceability.core.domain.event.DomainEvent;
import com.traceability.core.domain.event.SystemActor;
import com.traceability.core.domain.fund.Fund;
import com.traceability.core.domain.fund.OrganizationRef;
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
class FundCommandServiceGenesisIntegrationTest {

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
    void registerFund_persistsGenesisEventCorrectly() {
        String commandId = UUID.randomUUID().toString();
        String fundId = UUID.randomUUID().toString();
        OrganizationRef orgRef = new OrganizationRef("ORG-123");
        SystemActor actor = new SystemActor("test-harness");

        fundCommandService.registerFund(
                commandId,
                fundId,
                orgRef,
                "CAMP-1",
                "DONOR-1",
                "COP",
                1000L,
                actor
        );

        List<DomainEvent> stream = eventStorePort.loadStream(fundId);
        assertFalse(stream.isEmpty());
        assertEquals(1, stream.size());
        DomainEvent event = stream.get(0);
        assertEquals("FUND_REGISTERED", event.eventType().name());
        
        Fund reconstituted = Fund.rehydrate(fundId, stream.stream().map(DomainEvent::payload).toList(), stream.size());
        assertEquals("ORG-123", reconstituted.getOrganizationRef().value());
        assertEquals(1000L, reconstituted.getPledgedAmount());

        // Verify bypass
        org.mockito.Mockito.verify(identityPrincipalPort, org.mockito.Mockito.never()).resolvePrincipal(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void clearFundsGenesis_persistsGenesisEventCorrectly() {
        String commandId = UUID.randomUUID().toString();
        String fundId = UUID.randomUUID().toString();
        OrganizationRef orgRef = new OrganizationRef("ORG-123");
        SystemActor actor = new SystemActor("test-harness");

        fundCommandService.clearFundsGenesis(
                commandId,
                fundId,
                orgRef,
                "CAMP-1",
                "DONOR-1",
                "COP",
                5000L,
                "TX-001",
                actor
        );

        List<DomainEvent> stream = eventStorePort.loadStream(fundId);
        assertFalse(stream.isEmpty());
        assertEquals(1, stream.size());
        DomainEvent event = stream.get(0);
        assertEquals("FUNDS_CLEARED", event.eventType().name());
        
        Fund reconstituted = Fund.rehydrate(fundId, stream.stream().map(DomainEvent::payload).toList(), stream.size());
        assertEquals("ORG-123", reconstituted.getOrganizationRef().value());
        assertEquals(5000L, reconstituted.getClearedAmount());
    }
}
