package com.traceability.app;

import com.traceability.contracts.authorization.IdentityPrincipalPort;
import com.traceability.core.application.authorization.CrossOrganizationAccessException;
import com.traceability.core.application.authorization.InsufficientRoleException;
import com.traceability.core.application.command.FundCommandService;
import com.traceability.core.domain.event.HumanActor;
import com.traceability.core.domain.fund.OrganizationRef;
import identity.application.service.AddEmployeeService;
import identity.application.service.AssignAdministratorService;
import identity.application.service.CreateAccountService;
import identity.application.service.CreateOrganizationService;
import identity.application.service.IdentityPrincipalPortImpl;
import identity.domain.model.Account;
import identity.domain.model.Email;
import identity.domain.model.Organization;
import identity.domain.model.OrganizationType;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
    "crypto.anchor.poll.delay=9999999",
    "crypto.anchor.submit.delay=9999999",
    "crypto.anchor.stuck-monitor.delay=9999999",
    "crypto.web3j.private-key=0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
    "crypto.web3j.node-url=http://dummy-node",
    "spring.ai.openai.api-key=dummy-api-key"
})
// No @Import / @EnableMongoRepositories / mocks: identity beans come from TraceabilityApplication's own wiring.
class HumanActorIdentityIntegrationTest {

    @Container
    static MongoDBContainer mongoDBContainer = new MongoDBContainer(DockerImageName.parse("mongo:6.0"))
            .withCommand("--replSet", "rs0");

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongoDBContainer::getReplicaSetUrl);
    }

    @Autowired private FundCommandService fundCommandService;
    @Autowired private CreateAccountService createAccountService;
    @Autowired private CreateOrganizationService createOrganizationService;
    @Autowired private AddEmployeeService addEmployeeService;
    @Autowired private AssignAdministratorService assignAdministratorService;
    @Autowired private IdentityPrincipalPort identityPrincipalPort;

    private Organization org1;
    private Organization org2;
    private Account org1Admin;
    private Account org1Employee;
    private Account org2Admin;
    private boolean initialized = false;

    @BeforeEach
    void setUp() {
        if (initialized) return;

        assertNotNull(identityPrincipalPort);

        // 1. Create Organization 1
        Account org1Creator = createAccountService.createAccount(new Email(UUID.randomUUID() + "@test.com"), "Pass123!");
        org1 = createOrganizationService.createOrganization(OrganizationType.FOUNDATION, org1Creator.getAccountId());

        // 2. Add an Administrator to Org 1
        org1Admin = createAccountService.createAccount(new Email(UUID.randomUUID() + "@test.com"), "Pass123!");
        addEmployeeService.addEmployee(org1.getOrganizationId(), org1Admin.getAccountId());
        assignAdministratorService.assignAdministrator(org1.getOrganizationId(), org1Admin.getAccountId());

        // 3. Add an Employee (non-admin) to Org 1
        org1Employee = createAccountService.createAccount(new Email(UUID.randomUUID() + "@test.com"), "Pass123!");
        addEmployeeService.addEmployee(org1.getOrganizationId(), org1Employee.getAccountId());

        // 4. Create Organization 2
        Account org2Creator = createAccountService.createAccount(new Email(UUID.randomUUID() + "@test.com"), "Pass123!");
        org2 = createOrganizationService.createOrganization(OrganizationType.COMPANY, org2Creator.getAccountId());

        // 5. Add an Administrator to Org 2
        org2Admin = createAccountService.createAccount(new Email(UUID.randomUUID() + "@test.com"), "Pass123!");
        addEmployeeService.addEmployee(org2.getOrganizationId(), org2Admin.getAccountId());
        assignAdministratorService.assignAdministrator(org2.getOrganizationId(), org2Admin.getAccountId());

        initialized = true;
    }

    @Test
    void productionContext_wiresRealIdentityPrincipalPort() {
        assertThat(identityPrincipalPort).isInstanceOf(IdentityPrincipalPortImpl.class);
    }

    @Test
    void registerFund_asAdministrator_success() {
        OrganizationRef orgRef1 = new OrganizationRef(org1.getOrganizationId().value());
        
        assertDoesNotThrow(() -> {
            fundCommandService.registerFund(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                orgRef1,
                "CAMPAIGN-1",
                "DONOR-1",
                "USD",
                10000L,
                new HumanActor(org1Admin.getAccountId().value())
            );
        });
    }

    @Test
    void registerFund_asEmployee_throwsInsufficientRole() {
        OrganizationRef orgRef1 = new OrganizationRef(org1.getOrganizationId().value());

        assertThatThrownBy(() -> {
            fundCommandService.registerFund(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                orgRef1,
                "CAMPAIGN-2",
                "DONOR-2",
                "USD",
                5000L,
                new HumanActor(org1Employee.getAccountId().value())
            );
        }).isInstanceOf(InsufficientRoleException.class);
    }

    @Test
    void registerFund_asAdministratorOfDifferentOrg_throwsCrossOrganizationAccess() {
        OrganizationRef orgRef1 = new OrganizationRef(org1.getOrganizationId().value());

        assertThatThrownBy(() -> {
            fundCommandService.registerFund(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                orgRef1, // Requesting for Org 1
                "CAMPAIGN-3",
                "DONOR-3",
                "USD",
                5000L,
                new HumanActor(org2Admin.getAccountId().value()) // Actor is from Org 2
            );
        }).isInstanceOf(CrossOrganizationAccessException.class);
    }
}
