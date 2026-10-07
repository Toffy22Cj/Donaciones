package com.traceability.core.application.command;

import com.traceability.contracts.HashPort;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.contracts.authorization.AuthorizationRole;
import com.traceability.core.application.authorization.CommandType;
import com.traceability.core.application.authorization.CrossOrganizationAccessException;
import com.traceability.core.application.authorization.OrganizationBoundaryPolicy;
import com.traceability.core.application.authorization.RoleAuthorizationPolicy;
import com.traceability.core.application.port.out.EventStorePort;
import com.traceability.core.application.port.out.OutboxPort;
import com.traceability.core.domain.event.DomainEvent;
import com.traceability.core.domain.event.HumanActor;
import com.traceability.core.domain.event.SystemActor;
import com.traceability.core.domain.fund.Fund;
import com.traceability.core.domain.fund.OrganizationRef;
import com.traceability.core.infrastructure.authorization.TestIdentityPrincipalPort;
import com.traceability.core.infrastructure.authorization.TestIdentityPrincipalPortConfig;
import com.traceability.core.infrastructure.persistence.mongo.TraceabilityEventDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = {
        "core.projection.retry.delay=100",
        "core.projection.retry.timeout-minutes=5"
})
@Testcontainers
@Import(TestIdentityPrincipalPortConfig.class)
class HumanActorAuthorizationIntegrationTest {

    @SpyBean
    private TestIdentityPrincipalPort identityPrincipalPort;

    @SpyBean
    private OrganizationBoundaryPolicy organizationBoundaryPolicy;

    @SpyBean
    private RoleAuthorizationPolicy roleAuthorizationPolicy;

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
    private PhysicalAssetCommandService physicalAssetCommandService;

    @Autowired
    private EventStorePort eventStorePort;

    @Autowired
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void setup() {
        mongoTemplate.dropCollection(TraceabilityEventDocument.class);
        mongoTemplate.dropCollection("processed_commands");
        identityPrincipalPort.reset();
    }

    @AfterEach
    void clean() {
        mongoTemplate.dropCollection(TraceabilityEventDocument.class);
        mongoTemplate.dropCollection("processed_commands");
    }

    @Test
    void fundCommandService_withHumanActor_authorizesAndExecutes() {
        String accountId = "acc-admin-1";
        identityPrincipalPort.addPrincipal(accountId, "ORG-123", Set.of(AuthorizationRole.ADMINISTRATOR));

        String commandId = UUID.randomUUID().toString();
        String fundId = UUID.randomUUID().toString();
        OrganizationRef orgRef = new OrganizationRef("ORG-123");
        HumanActor actor = new HumanActor(accountId);

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

        // Verify that the aggregate was actually executed and saved
        List<DomainEvent> stream = eventStorePort.loadStream(fundId);
        assertFalse(stream.isEmpty());
        assertEquals("FUND_REGISTERED", stream.get(0).eventType().name());

        InOrder inOrder = Mockito.inOrder(identityPrincipalPort, organizationBoundaryPolicy, roleAuthorizationPolicy);
        inOrder.verify(identityPrincipalPort).resolvePrincipal(accountId);
        inOrder.verify(organizationBoundaryPolicy).assertBelongs("ORG-123", "ORG-123");
        inOrder.verify(roleAuthorizationPolicy).authorize(any(AuthorizationPrincipal.class), eq(CommandType.REGISTER_FUND));
    }

    @Test
    void fundCommandService_withHumanActorWithoutOrganization_failsWithException() {
        String accountId = "acc-no-org";
        identityPrincipalPort.addPrincipal(accountId, null, Set.of(AuthorizationRole.ADMINISTRATOR));

        String commandId = UUID.randomUUID().toString();
        String fundId = UUID.randomUUID().toString();
        OrganizationRef orgRef = new OrganizationRef("ORG-123");
        HumanActor actor = new HumanActor(accountId);

        org.junit.jupiter.api.Assertions.assertThrows(
                com.traceability.core.application.authorization.CrossOrganizationAccessException.class,
                () -> fundCommandService.registerFund(
                        commandId, fundId, orgRef, "CAMP-1", "DONOR-1", "COP", 1000L, actor
                )
        );
    }

    @Test
    void fundCommandService_withHumanActorWithoutRequiredRole_failsWithException() {
        String accountId = "acc-emp-1";
        identityPrincipalPort.addPrincipal(accountId, "ORG-123", Set.of(AuthorizationRole.EMPLOYEE)); // Needs ADMINISTRATOR

        String commandId = UUID.randomUUID().toString();
        String fundId = UUID.randomUUID().toString();
        OrganizationRef orgRef = new OrganizationRef("ORG-123");
        HumanActor actor = new HumanActor(accountId);

        org.junit.jupiter.api.Assertions.assertThrows(
                com.traceability.core.application.authorization.InsufficientRoleException.class,
                () -> fundCommandService.registerFund(
                        commandId, fundId, orgRef, "CAMP-1", "DONOR-1", "COP", 1000L, actor
                )
        );
    }

    @Test
    void fundCommandService_withNonExistentHumanActor_failsWithException() {
        String commandId = UUID.randomUUID().toString();
        String fundId = UUID.randomUUID().toString();
        OrganizationRef orgRef = new OrganizationRef("ORG-123");
        HumanActor actor = new HumanActor("acc-nonexistent"); // Not added to TestIdentityPrincipalPort

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> fundCommandService.registerFund(
                        commandId, fundId, orgRef, "CAMP-1", "DONOR-1", "COP", 1000L, actor
                )
        );
    }

    @Test
    void physicalAssetCommandService_withHumanActor_authorizesAndExecutes() {
        String accountId = "acc-emp-1";
        identityPrincipalPort.addPrincipal(accountId, "ORG-456", Set.of(AuthorizationRole.EMPLOYEE));

        String fundId = UUID.randomUUID().toString();
        registerFund(fundId, "ORG-456");

        String commandId = UUID.randomUUID().toString();
        HumanActor actor = new HumanActor(accountId);

        physicalAssetCommandService.registerPhysicalAsset(
                commandId,
                fundId,
                "ORG-456",
                "Tents",
                new BigDecimal("100"),
                "Units",
                "CUST-1",
                "LOC-1",
                "ALLOC-1",
                null,
                actor
        );

        InOrder inOrder = Mockito.inOrder(identityPrincipalPort, organizationBoundaryPolicy, roleAuthorizationPolicy);
        inOrder.verify(identityPrincipalPort).resolvePrincipal(accountId);
        inOrder.verify(organizationBoundaryPolicy).assertBelongs("ORG-456", "ORG-456");
        inOrder.verify(roleAuthorizationPolicy).authorize(any(AuthorizationPrincipal.class), eq(CommandType.REGISTER_PHYSICAL_ASSET));
        assertEquals(1, physicalAssetEvents().size());
    }

    @Test
    void physicalAssetCommandService_withHumanActor_fundOfOtherOrganization_rejectedWithoutEffects() {
        // Employee of ORG-456 declares its own organization but points at a Fund owned by ORG-OTHER:
        // the boundary check against the declared organizationRef alone would pass (ADR-029, Path A).
        String accountId = "acc-emp-2";
        identityPrincipalPort.addPrincipal(accountId, "ORG-456", Set.of(AuthorizationRole.EMPLOYEE));

        String foreignFundId = UUID.randomUUID().toString();
        registerFund(foreignFundId, "ORG-OTHER");

        assertThrows(CrossOrganizationAccessException.class, () ->
                physicalAssetCommandService.registerPhysicalAsset(
                        UUID.randomUUID().toString(),
                        foreignFundId,
                        "ORG-456",
                        "Tents",
                        new BigDecimal("100"),
                        "Units",
                        "CUST-1",
                        "LOC-1",
                        "ALLOC-1",
                        null,
                        new HumanActor(accountId)
                ));

        assertTrue(physicalAssetEvents().isEmpty(), "No PhysicalAsset may be persisted for a foreign Fund");
        assertEquals(1, eventStorePort.loadStream(foreignFundId).size(), "Foreign Fund stream must be untouched");
        verify(outboxPort, never()).save(any());
        verify(identityPrincipalPort, never()).resolvePrincipal(accountId);
    }

    private void registerFund(String fundId, String organizationId) {
        fundCommandService.registerFund(UUID.randomUUID().toString(), fundId, new OrganizationRef(organizationId),
                "CAMP-1", "DONOR-1", "COP", 1000L, new SystemActor("test-setup"));
    }

    private List<TraceabilityEventDocument> physicalAssetEvents() {
        return mongoTemplate.findAll(TraceabilityEventDocument.class).stream()
                .filter(e -> "PhysicalAsset".equals(e.getAggregateType()))
                .toList();
    }
}
