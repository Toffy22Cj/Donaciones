package com.traceability.app.infrastructure.convocatoria;

import com.traceability.app.TraceabilityApplication;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.contracts.authorization.AuthorizationRole;
import com.traceability.contracts.authorization.IdentityPrincipalPort;
import com.traceability.contracts.campaign.CampaignInKindEligibilityPort;
import com.traceability.convocatoria.application.command.CloseConvocatoriaCommand;
import com.traceability.convocatoria.application.command.CreateConvocatoriaCommand;
import com.traceability.convocatoria.application.port.out.OrganizationVerificationPort;
import com.traceability.convocatoria.application.service.CampaignInKindEligibilityService;
import com.traceability.convocatoria.application.service.ConvocatoriaLifecycleService;
import com.traceability.convocatoria.domain.model.ConvocatoriaConfiguration;
import com.traceability.convocatoria.domain.model.DonationType;
import com.traceability.convocatoria.domain.model.Visibility;
import com.traceability.core.application.authorization.OrganizationBoundaryPolicy;
import com.traceability.core.application.authorization.RoleAuthorizationPolicy;
import com.traceability.core.application.command.CommandRetryTemplate;
import com.traceability.core.application.command.PhysicalAssetCommandService;
import com.traceability.core.application.exception.InKindCampaignClosedException;
import com.traceability.core.application.port.out.EventStorePort;
import com.traceability.core.application.port.out.ProcessedCommandRepositoryPort;
import com.traceability.core.application.service.TransactionalEventPublisher;
import com.traceability.core.domain.event.SystemActor;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * D-CAMPAIGN (plan-d-campaign.md §3.4): {@link CampaignInKindEligibilityPort} en el contexto completo de {@code app},
 * con la implementación real de {@code convocatoria}. Identidad va con mocks (la verificación de organización tiene
 * su propio test de wiring).
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
class CampaignInKindEligibilityWiringIntegrationTest {

    private static final String ORG = "org-in-kind";
    private static final String ADMIN = "admin-in-kind";
    private static final SystemActor SYSTEM = new SystemActor("d-campaign-wiring");

    @Container
    static MongoDBContainer mongoDBContainer = new MongoDBContainer(DockerImageName.parse("mongo:6.0"))
            .withCommand("--replSet", "rs0");

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongoDBContainer::getReplicaSetUrl);
    }

    @MockitoBean private IdentityPrincipalPort identityPrincipalPort;
    @MockitoBean private OrganizationVerificationPort organizationVerificationPort;

    @Autowired private ApplicationContext context;
    @Autowired private CampaignInKindEligibilityPort port;
    @Autowired private PhysicalAssetCommandService assets;
    @Autowired private ConvocatoriaLifecycleService lifecycle;
    @Autowired private MongoTemplate mongoTemplate;

    @BeforeEach
    void identity() {
        when(identityPrincipalPort.resolvePrincipal(ADMIN)).thenReturn(
                new AuthorizationPrincipal(ADMIN, ORG, Set.of(AuthorizationRole.ADMINISTRATOR), null));
        when(organizationVerificationPort.isVerified(any())).thenReturn(true);
    }

    @Test
    void singleProductionImplementation_isTheConvocatoriaService() {
        Map<String, CampaignInKindEligibilityPort> beans = context.getBeansOfType(CampaignInKindEligibilityPort.class);

        assertThat(beans).hasSize(1);
        assertThat(beans.values().iterator().next()).isInstanceOf(CampaignInKindEligibilityService.class);
        assertThat(ReflectionTestUtils.getField(assets, "campaignInKindEligibility")).isSameAs(port);
    }

    @Test
    void coreTestStub_isNotOnTheProductionClasspath() {
        assertThatThrownBy(() -> Class.forName("com.traceability.core.support.StubCampaignInKindEligibilityPort"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void withoutAnImplementation_theContextDoesNotStart() {
        // Puerto obligatorio (Q2): un error de cableado nunca queda oculto como un rechazo silencioso.
        new ApplicationContextRunner()
                .withBean(CommandRetryTemplate.class, () -> mock(CommandRetryTemplate.class))
                .withBean(ProcessedCommandRepositoryPort.class, () -> mock(ProcessedCommandRepositoryPort.class))
                .withBean(EventStorePort.class, () -> mock(EventStorePort.class))
                .withBean(TransactionalEventPublisher.class, () -> mock(TransactionalEventPublisher.class))
                .withBean(RoleAuthorizationPolicy.class, () -> mock(RoleAuthorizationPolicy.class))
                .withBean(OrganizationBoundaryPolicy.class, () -> mock(OrganizationBoundaryPolicy.class))
                .withBean(IdentityPrincipalPort.class, () -> mock(IdentityPrincipalPort.class))
                .withBean(PhysicalAssetCommandService.class)
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("CampaignInKindEligibilityPort");
                });
    }

    @Test
    void inKindAssetWithARealOpenCampaign_isRegisteredInV3WithItsCampaignRef() {
        String campaignRef = inKindCampaign();

        String assetId = registerInKind(campaignRef);

        Document genesis = mongoTemplate.findOne(
                org.springframework.data.mongodb.core.query.Query.query(
                        org.springframework.data.mongodb.core.query.Criteria.where("streamId").is(assetId)),
                Document.class, "event_store");
        assertThat(genesis.getString("schemaVersion")).isEqualTo("3.0");
        assertThat(genesis.get("payload", Document.class).getString("campaignRef")).isEqualTo(campaignRef);
    }

    @Test
    void closedRealCampaign_isRejectedWithoutWritingTheAsset() {
        String campaignRef = inKindCampaign();
        lifecycle.closeConvocatoria(new CloseConvocatoriaCommand(UUID.randomUUID().toString(), ADMIN, campaignRef));
        long before = mongoTemplate.getCollection("event_store").countDocuments();

        assertThatThrownBy(() -> registerInKind(campaignRef)).isInstanceOf(InKindCampaignClosedException.class);

        assertThat(mongoTemplate.getCollection("event_store").countDocuments()).isEqualTo(before);
    }

    private String inKindCampaign() {
        ConvocatoriaConfiguration inKind = new ConvocatoriaConfiguration(EnumSet.of(DonationType.IN_KIND), null, null,
                null, null, null);
        return lifecycle.createConvocatoria(new CreateConvocatoriaCommand(UUID.randomUUID().toString(), ADMIN, ORG,
                "Mantas", null, Visibility.PUBLIC, Instant.parse("2027-01-01T00:00:00Z"), // futura: deuda D-2 (plan B6-a)
                Instant.parse("2027-12-31T00:00:00Z"), inKind)).campaignRef();
    }

    private String registerInKind(String campaignRef) {
        String donorRef = "donor-" + UUID.randomUUID();
        assets.registerPhysicalAssetFromDonation(UUID.randomUUID().toString(), ORG, donorRef, "BLANKETS",
                BigDecimal.TEN, "UNITS", "CUST-1", "WH-1", campaignRef, SYSTEM);
        return mongoTemplate.findOne(
                org.springframework.data.mongodb.core.query.Query.query(
                        org.springframework.data.mongodb.core.query.Criteria.where("payload.donorRef").is(donorRef)),
                Document.class, "event_store").getString("streamId");
    }
}
