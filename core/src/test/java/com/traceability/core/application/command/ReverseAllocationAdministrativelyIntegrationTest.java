package com.traceability.core.application.command;

import com.traceability.contracts.HashPort;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.contracts.authorization.AuthorizationRole;
import com.traceability.core.application.authorization.CommandType;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;

@SpringBootTest(properties = {
        "core.projection.retry.delay=100",
        "core.projection.retry.timeout-minutes=5"
})
@Import(TestIdentityPrincipalPortConfig.class)
public class ReverseAllocationAdministrativelyIntegrationTest {

    @SpyBean
    private TestIdentityPrincipalPort identityPrincipalPort;

    @SpyBean
    private OrganizationBoundaryPolicy organizationBoundaryPolicy;

    @SpyBean
    private RoleAuthorizationPolicy roleAuthorizationPolicy;

    @SpyBean
    private EventStorePort eventStorePort;

    @Autowired
    private FundCommandService fundCommandService;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private OutboxPort outboxPort;

    @MockBean
    private HashPort hashPort; // prevent errors in security infra if picked up

    static MongoDBContainer mongoDBContainer = new MongoDBContainer(DockerImageName.parse("mongo:6.0"))
            .withCommand("--replSet", "rs0");

    static {
        mongoDBContainer.start();
    }

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

    @BeforeEach
    void setUp() {
        mongoTemplate.dropCollection(TraceabilityEventDocument.class);
        mongoTemplate.dropCollection("outbox");
        identityPrincipalPort.reset();
        Mockito.reset(identityPrincipalPort, organizationBoundaryPolicy, roleAuthorizationPolicy, eventStorePort);
    }

    @AfterEach
    void tearDown() {
        mongoTemplate.dropCollection(TraceabilityEventDocument.class);
        mongoTemplate.dropCollection("outbox");
    }

    private void createFundWithAllocation(String fundId, String organizationId, String allocationId) {
        SystemActor systemActor = new SystemActor("Setup");
        fundCommandService.clearFundsGenesis(UUID.randomUUID().toString(), fundId, new OrganizationRef(organizationId), "camp1", "donor1", "USD", 1000L, "src1", systemActor);
        fundCommandService.requestAllocation(UUID.randomUUID().toString(), fundId, allocationId, 100L, systemActor);

        // Assert allocation requested
        List<DomainEvent> events = eventStorePort.loadStream(fundId);
        assertTrue(events.stream().anyMatch(e -> e.eventType().name().equals("ALLOCATION_REQUESTED")));
        Mockito.reset(eventStorePort);
    }

    @Test
    void testA_F_humanActorAuthorizedAndOrder() {
        String accountId = "account-authorized";
        String organizationId = "ORG-1";
        String fundId = UUID.randomUUID().toString();
        String allocationId = "alloc-1";
        createFundWithAllocation(fundId, organizationId, allocationId);

        identityPrincipalPort.addPrincipal(accountId, organizationId, Set.of(AuthorizationRole.ADMINISTRATOR));

        HumanActor humanActor = new HumanActor(accountId);

        fundCommandService.reverseAllocationAdministratively(
                UUID.randomUUID().toString(),
                fundId,
                allocationId,
                "Admin reversal",
                humanActor
        );

        // Verification A & F: Correct persistence and execution order
        List<DomainEvent> events = eventStorePort.loadStream(fundId);
        assertTrue(events.stream().anyMatch(e -> e.eventType().name().equals("ALLOCATION_REVERSED")));

        InOrder inOrder = Mockito.inOrder(identityPrincipalPort, organizationBoundaryPolicy, roleAuthorizationPolicy, eventStorePort);
        inOrder.verify(identityPrincipalPort).resolvePrincipal(accountId);
        inOrder.verify(organizationBoundaryPolicy).assertBelongs(organizationId, organizationId);
        inOrder.verify(roleAuthorizationPolicy).authorize(any(AuthorizationPrincipal.class), eq(CommandType.REVERSE_ALLOCATION_ADMINISTRATIVELY));
        inOrder.verify(eventStorePort).append(eq(fundId), any(), anyLong(), any(), any());
    }

    @Test
    void testB_humanActorIncorrectOrganization() {
        String accountId = "account-bad-org";
        String fundOrgId = "ORG-1";
        String userOrgId = "ORG-2";
        String fundId = UUID.randomUUID().toString();
        String allocationId = "alloc-1";
        createFundWithAllocation(fundId, fundOrgId, allocationId);

        identityPrincipalPort.addPrincipal(accountId, userOrgId, Set.of(AuthorizationRole.ADMINISTRATOR));

        HumanActor humanActor = new HumanActor(accountId);

        try {
            fundCommandService.reverseAllocationAdministratively(
                    UUID.randomUUID().toString(), fundId, allocationId, "Admin reversal", humanActor
            );
        } catch (Exception e) {
            // Expected boundary policy exception
        }

        // Verify aggregate not executed
        Mockito.verify(roleAuthorizationPolicy, Mockito.never()).authorize(any(), any());
        Mockito.verify(eventStorePort, Mockito.never()).append(eq(fundId), any(), anyLong(), any(), any());
    }

    @Test
    void testC_humanActorWithoutOrganization() {
        String accountId = "account-no-org";
        String fundOrgId = "ORG-1";
        String fundId = UUID.randomUUID().toString();
        String allocationId = "alloc-1";
        createFundWithAllocation(fundId, fundOrgId, allocationId);

        identityPrincipalPort.addPrincipal(accountId, null, Set.of(AuthorizationRole.ADMINISTRATOR));

        HumanActor humanActor = new HumanActor(accountId);

        try {
            fundCommandService.reverseAllocationAdministratively(
                    UUID.randomUUID().toString(), fundId, allocationId, "Admin reversal", humanActor
            );
        } catch (Exception e) {
            // Expected boundary policy exception
        }

        // Verify aggregate not executed
        Mockito.verify(roleAuthorizationPolicy, Mockito.never()).authorize(any(), any());
        Mockito.verify(eventStorePort, Mockito.never()).append(eq(fundId), any(), anyLong(), any(), any());
    }

    @Test
    void testD_humanActorInsufficientRole() {
        String accountId = "account-bad-role";
        String organizationId = "ORG-1";
        String fundId = UUID.randomUUID().toString();
        String allocationId = "alloc-1";
        createFundWithAllocation(fundId, organizationId, allocationId);

        identityPrincipalPort.addPrincipal(accountId, organizationId, Set.of(AuthorizationRole.EMPLOYEE));

        HumanActor humanActor = new HumanActor(accountId);

        try {
            fundCommandService.reverseAllocationAdministratively(
                    UUID.randomUUID().toString(), fundId, allocationId, "Admin reversal", humanActor
            );
        } catch (Exception e) {
            // Expected insufficient role exception
        }

        Mockito.verify(roleAuthorizationPolicy).authorize(any(), eq(CommandType.REVERSE_ALLOCATION_ADMINISTRATIVELY));
        // Verify aggregate not executed
        Mockito.verify(eventStorePort, Mockito.never()).append(eq(fundId), any(), anyLong(), any(), any());
    }

    @Test
    void testE_nonExistentAccount() {
        String accountId = "account-nonexistent";
        String fundId = UUID.randomUUID().toString();
        String allocationId = "alloc-1";
        createFundWithAllocation(fundId, "ORG-1", allocationId);

        // Don't add principal, so it fails in TestIdentityPrincipalPort
        HumanActor humanActor = new HumanActor(accountId);

        try {
            fundCommandService.reverseAllocationAdministratively(
                    UUID.randomUUID().toString(), fundId, allocationId, "Admin reversal", humanActor
            );
        } catch (Exception e) {
            // Expected missing account
        }

        Mockito.verify(identityPrincipalPort).resolvePrincipal(accountId);
        Mockito.verify(organizationBoundaryPolicy, Mockito.never()).assertBelongs(any(), any());
        Mockito.verify(eventStorePort, Mockito.never()).append(eq(fundId), any(), anyLong(), any(), any());
    }

    @Test
    void testG_sagaBypassOnOriginalReverseAllocation() {
        String fundId = UUID.randomUUID().toString();
        String allocationId = "alloc-1";
        createFundWithAllocation(fundId, "ORG-1", allocationId);

        SystemActor systemActor = new SystemActor("AssetRegisteredSagaPolicy");

        // The saga calls the ORIGINAL reverseAllocation method
        fundCommandService.reverseAllocation(
                UUID.randomUUID().toString(),
                fundId,
                allocationId,
                "Saga reversal",
                systemActor
        );

        List<DomainEvent> events = eventStorePort.loadStream(fundId);
        assertTrue(events.stream().anyMatch(e -> e.eventType().name().equals("ALLOCATION_REVERSED")));

        // Verify authorize() was NEVER called because original method does not have authorize() inside
        Mockito.verify(identityPrincipalPort, Mockito.never()).resolvePrincipal(any());
        Mockito.verify(organizationBoundaryPolicy, Mockito.never()).assertBelongs(any(), any());
        Mockito.verify(roleAuthorizationPolicy, Mockito.never()).authorize(any(), any());
    }
}
