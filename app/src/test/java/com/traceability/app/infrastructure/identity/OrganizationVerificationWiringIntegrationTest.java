package com.traceability.app.infrastructure.identity;

import com.traceability.app.TraceabilityApplication;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.contracts.authorization.IdentityPrincipalPort;
import com.traceability.convocatoria.application.port.out.OrganizationVerificationPort;
import com.traceability.convocatoria.application.service.ConvocatoriaLifecycleService;
import com.traceability.convocatoria.application.service.DonationIntentService;
import identity.application.service.BootstrapPlatformAuthorityService;
import identity.application.service.CreateAccountService;
import identity.application.service.CreateOrganizationService;
import identity.application.service.RejectOrganizationService;
import identity.application.service.RequestOrganizationInformationService;
import identity.application.service.VerifyOrganizationService;
import identity.domain.model.AccountId;
import identity.domain.model.AuditActor;
import identity.domain.model.Email;
import identity.domain.model.OrganizationId;
import identity.domain.model.OrganizationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Wiring real de {@link OrganizationVerificationPort} en el contexto de {@code app}: sin mocks ni fakes, contra
 * el {@code Organization} de {@code identity} persistido en Mongo. Clase de arranque explícita: el paquete padre
 * contiene una {@code @SpringBootConfiguration} anidada de otro test que, si no, se tomaría por búsqueda.
 */
@SpringBootTest(classes = TraceabilityApplication.class)
@Testcontainers
@TestPropertySource(properties = {
    "crypto.anchor.poll.delay=9999999",
    "crypto.anchor.submit.delay=9999999",
    "crypto.anchor.stuck-monitor.delay=9999999",
    "crypto.web3j.private-key=0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
    "crypto.web3j.node-url=http://dummy-node",
    "spring.ai.openai.api-key=dummy-api-key"
})
class OrganizationVerificationWiringIntegrationTest {

    @Container
    static MongoDBContainer mongoDBContainer = new MongoDBContainer(DockerImageName.parse("mongo:6.0"))
            .withCommand("--replSet", "rs0");

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongoDBContainer::getReplicaSetUrl);
    }

    private final AuditActor testActor = new AuditActor.AccountAuditActor(AccountId.generate());

    @Autowired private ApplicationContext context;
    @Autowired private OrganizationVerificationPort organizationVerificationPort;
    @Autowired private ConvocatoriaLifecycleService convocatoriaLifecycleService;
    @Autowired private DonationIntentService donationIntentService;
    @Autowired private IdentityPrincipalPort identityPrincipalPort;
    @Autowired private CreateAccountService createAccountService;
    @Autowired private CreateOrganizationService createOrganizationService;
    @Autowired private BootstrapPlatformAuthorityService bootstrapPlatformAuthorityService;
    @Autowired private VerifyOrganizationService verifyOrganizationService;
    @Autowired private RejectOrganizationService rejectOrganizationService;
    @Autowired private RequestOrganizationInformationService requestOrganizationInformationService;

    private static AuthorizationPrincipal platformAdmin;

    @BeforeEach
    void bootstrapPlatformAdminOnce() {
        if (platformAdmin != null) return;
        String email = UUID.randomUUID() + "@platform.test";
        createAccountService.createAccount(new Email(email), "Pass123!Pass123!");
        AccountId adminId = bootstrapPlatformAuthorityService.bootstrap(email);
        platformAdmin = identityPrincipalPort.resolvePrincipal(adminId.value());
    }

    @Test
    void singleProductionImplementation_isTheIdentityAdapter() {
        Map<String, OrganizationVerificationPort> beans = context.getBeansOfType(OrganizationVerificationPort.class);

        assertThat(beans).hasSize(1);
        assertThat(beans.values().iterator().next()).isInstanceOf(OrganizationVerificationAdapter.class);
    }

    @Test
    void convocatoriaServices_receiveTheSameAdapter() {
        assertThat(ReflectionTestUtils.getField(convocatoriaLifecycleService, "organizationVerificationPort"))
                .isSameAs(organizationVerificationPort);
        assertThat(ReflectionTestUtils.getField(donationIntentService, "organizationVerificationPort"))
                .isSameAs(organizationVerificationPort);
    }

    @Test
    void convocatoriaTestFake_isNotOnTheProductionClasspath() {
        assertThatThrownBy(() -> Class.forName(
                "com.traceability.convocatoria.support.FakeOrganizationVerificationPort"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void pendingOrganization_isNotVerified_untilPlatformVerifiesIt() {
        OrganizationId orgId = newOrganization();
        assertThat(organizationVerificationPort.isVerified(orgId.value())).isFalse();

        verifyOrganizationService.verifyOrganization(platformAdmin, orgId);

        assertThat(organizationVerificationPort.isVerified(orgId.value())).isTrue();
    }

    @Test
    void organizationNeedingMoreInformation_isNotVerified() {
        OrganizationId orgId = newOrganization();
        requestOrganizationInformationService.requestOrganizationInformation(platformAdmin, orgId, "Falta el RUT");

        assertThat(organizationVerificationPort.isVerified(orgId.value())).isFalse();
    }

    @Test
    void rejectedOrganization_isNotVerified() {
        OrganizationId orgId = newOrganization();
        rejectOrganizationService.rejectOrganization(platformAdmin, orgId);

        assertThat(organizationVerificationPort.isVerified(orgId.value())).isFalse();
    }

    @Test
    void unknownOrganization_isNotVerified() {
        assertThat(organizationVerificationPort.isVerified(OrganizationId.generate().value())).isFalse();
    }

    private OrganizationId newOrganization() {
        var representative = createAccountService.createAccount(new Email(UUID.randomUUID() + "@test.com"), "Pass123!Pass123!");
        return createOrganizationService
                .createOrganization(testActor, OrganizationType.FOUNDATION, representative.getAccountId())
                .getOrganizationId();
    }
}
