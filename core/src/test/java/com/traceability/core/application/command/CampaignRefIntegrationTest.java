package com.traceability.core.application.command;

import com.traceability.contracts.HashPort;
import com.traceability.contracts.authorization.AuthorizationRole;
import com.traceability.contracts.campaign.InKindEligibility;
import com.traceability.core.application.authorization.CrossOrganizationAccessException;
import com.traceability.core.application.exception.CampaignNotEligibleForInKindDonationException;
import com.traceability.core.application.exception.InKindCampaignClosedException;
import com.traceability.core.application.exception.InKindCampaignNotFoundException;
import com.traceability.core.application.exception.InKindCampaignOfOtherOrganizationException;
import com.traceability.core.application.exception.InKindNotAcceptedByCampaignException;
import com.traceability.core.application.port.out.EventStorePort;
import com.traceability.core.domain.event.DomainEventPayload;
import com.traceability.core.domain.event.HumanActor;
import com.traceability.core.domain.event.SystemActor;
import com.traceability.core.domain.fund.OrganizationRef;
import com.traceability.core.domain.physicalasset.payloads.AssetRegisteredV3Payload;
import com.traceability.core.infrastructure.authorization.TestIdentityPrincipalPort;
import com.traceability.core.infrastructure.authorization.TestIdentityPrincipalPortConfig;
import com.traceability.core.infrastructure.persistence.mongo.TraceabilityEventDocument;
import com.traceability.core.support.StubCampaignInKindEligibilityPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
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
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * D-CAMPAIGN (ADR-029 Enmienda 1; plan-d-campaign.md): campaignRef en el registro de PhysicalAsset.
 * Camino A: heredado del Fund, sin comprobar que la convocatoria siga OPEN. Camino B: opcional, validado con
 * CampaignInKindEligibilityPort después de autorizar y antes de persistir; un rechazo no deja efectos.
 */
@SpringBootTest(properties = {
        "core.projection.retry.delay=99999999",
        "core.projection.retry.timeout-minutes=1"
})
@Testcontainers
@Import(TestIdentityPrincipalPortConfig.class)
class CampaignRefIntegrationTest {

    private static final String ORG = "ORG-CAMP";
    private static final String EMPLOYEE = "acc-camp-employee";
    private static final String OTHER_ORG_EMPLOYEE = "acc-other-employee";
    private static final SystemActor SYSTEM = new SystemActor("d-campaign-tests");

    @SpyBean private TestIdentityPrincipalPort identityPrincipalPort;
    @MockBean private HashPort hashPort;

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
    static class TestConfig {}

    @Autowired private PhysicalAssetCommandService assets;
    @Autowired private FundCommandService funds;
    @Autowired private EventStorePort eventStore;
    @Autowired private StubCampaignInKindEligibilityPort campaigns;
    @Autowired private MongoTemplate mongoTemplate;

    @BeforeEach
    void setup() {
        mongoTemplate.dropCollection(TraceabilityEventDocument.class);
        mongoTemplate.dropCollection("processed_commands");
        identityPrincipalPort.reset();
        identityPrincipalPort.addPrincipal(EMPLOYEE, ORG, Set.of(AuthorizationRole.EMPLOYEE));
        identityPrincipalPort.addPrincipal(OTHER_ORG_EMPLOYEE, "ORG-OTHER", Set.of(AuthorizationRole.EMPLOYEE));
        campaigns.reset();
        when(hashPort.canonicalizeAndHash(any(), any())).thenAnswer(inv -> UUID.randomUUID().toString());
    }

    @AfterEach
    void clean() {
        mongoTemplate.dropCollection(TraceabilityEventDocument.class);
        mongoTemplate.dropCollection("processed_commands");
    }

    // --- Camino A (D2) ---

    @Test
    void caminoA_inheritsTheFundCampaignRef() {
        String assetId = registerCaminoA("CAMP-A");

        AssetRegisteredV3Payload genesis = genesis(assetId);
        assertThat(genesis.campaignRef()).isEqualTo("CAMP-A");
        assertThat(storedSchemaVersion(assetId)).isEqualTo("3.0");
        assertThat(storedPayloadField(assetId, "campaignRef")).as("campaignRef dentro del payload con hash").isEqualTo("CAMP-A");
    }

    @Test
    void caminoA_fundWithoutCampaign_givesAnAssetWithoutCampaign() {
        String assetId = registerCaminoA(null);

        assertThat(genesis(assetId).campaignRef()).isNull();
    }

    @Test
    void caminoA_doesNotCheckThatTheCampaignIsStillOpen() {
        // Decisión escrita (plan §3.2.3): gastar fondos ya recaudados tras el cierre es legítimo (ADR-037 D1).
        campaigns.answer("CAMP-CLOSED", InKindEligibility.CAMPAIGN_CLOSED);

        String assetId = registerCaminoA("CAMP-CLOSED");

        assertThat(genesis(assetId).campaignRef()).isEqualTo("CAMP-CLOSED");
        assertThat(campaigns.queries()).as("el Camino A no consulta la convocatoria").isEmpty();
    }

    // --- Camino B (D3) ---

    @Test
    void caminoB_eligibleCampaign_isRecordedInV3() {
        campaigns.answer("CAMP-B", InKindEligibility.ELIGIBLE);

        String assetId = registerCaminoB(UUID.randomUUID().toString(), "CAMP-B", new HumanActor(EMPLOYEE));

        AssetRegisteredV3Payload genesis = genesis(assetId);
        assertThat(genesis.campaignRef()).isEqualTo("CAMP-B");
        assertThat(genesis.donationRef()).isNotNull();
        assertThat(campaigns.queries()).containsExactly("CAMP-B");
    }

    @Test
    void caminoB_withoutCampaign_doesNotQueryTheCampaign() {
        String assetId = registerCaminoB(UUID.randomUUID().toString(), null, new HumanActor(EMPLOYEE));

        assertThat(genesis(assetId).campaignRef()).isNull();
        assertThat(campaigns.queries()).isEmpty();
    }

    static Stream<Arguments> rejections() {
        return Stream.of(
                Arguments.of(InKindEligibility.CAMPAIGN_NOT_FOUND, InKindCampaignNotFoundException.class),
                Arguments.of(InKindEligibility.OTHER_ORGANIZATION, InKindCampaignOfOtherOrganizationException.class),
                Arguments.of(InKindEligibility.CAMPAIGN_CLOSED, InKindCampaignClosedException.class),
                Arguments.of(InKindEligibility.IN_KIND_NOT_ACCEPTED, InKindNotAcceptedByCampaignException.class));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejections")
    void caminoB_rejection_hasItsNamedException_andLeavesNoEffects(InKindEligibility reason,
                                                                  Class<? extends RuntimeException> expected) {
        campaigns.answer("CAMP-X", reason);
        String commandId = UUID.randomUUID().toString();

        assertThatThrownBy(() -> registerCaminoB(commandId, "CAMP-X", new HumanActor(EMPLOYEE)))
                .isExactlyInstanceOf(expected)
                .isInstanceOf(CampaignNotEligibleForInKindDonationException.class);

        assertThat(mongoTemplate.count(new Query(), TraceabilityEventDocument.class)).as("sin eventos").isZero();
        assertThat(mongoTemplate.count(new Query(Criteria.where("_id").is(commandId)), "processed_commands"))
                .as("sin reclamo del commandId").isZero();
    }

    @Test
    void notFoundAndOtherOrganization_lookIdenticalFromOutside() {
        campaigns.answer("CAMP-MISSING", InKindEligibility.CAMPAIGN_NOT_FOUND);
        campaigns.answer("CAMP-FOREIGN", InKindEligibility.OTHER_ORGANIZATION);

        Throwable notFound = catchRejection("CAMP-MISSING");
        Throwable foreign = catchRejection("CAMP-FOREIGN");

        assertThat(notFound.getMessage()).isEqualTo(foreign.getMessage());
        assertThat(notFound.getMessage()).doesNotContain("CAMP-MISSING").doesNotContain("organization");
    }

    @Test
    void unauthorizedActor_isRejectedBeforeTheCampaignIsQueried() {
        campaigns.answer("CAMP-PROBE", InKindEligibility.ELIGIBLE);

        assertThatThrownBy(() -> registerCaminoB(UUID.randomUUID().toString(), "CAMP-PROBE",
                new HumanActor(OTHER_ORG_EMPLOYEE)))
                .isInstanceOf(CrossOrganizationAccessException.class);

        assertThat(campaigns.queries()).as("autorizar antes de consultar: no se puede sondear un campaignRef").isEmpty();
    }

    @Test
    void sameCommandIdTwice_registersASingleAsset() {
        campaigns.answer("CAMP-B", InKindEligibility.ELIGIBLE);
        String commandId = UUID.randomUUID().toString();

        registerCaminoB(commandId, "CAMP-B", new HumanActor(EMPLOYEE));
        assets.registerPhysicalAssetFromDonation(commandId, ORG, "DONOR-1", "BLANKETS", BigDecimal.TEN, "UNITS",
                "CUST-1", "WH-1", "CAMP-B", new HumanActor(EMPLOYEE));

        assertThat(mongoTemplate.count(new Query(), TraceabilityEventDocument.class)).isEqualTo(1);
    }

    // --- utilidades ---

    private String registerCaminoA(String fundCampaignRef) {
        String fundId = UUID.randomUUID().toString();
        String allocationId = UUID.randomUUID().toString();
        funds.clearFundsGenesis(UUID.randomUUID().toString(), fundId, new OrganizationRef(ORG), fundCampaignRef,
                "DONOR-1", "COP", 1000L, "SRC-1", SYSTEM);
        assets.registerPhysicalAsset(UUID.randomUUID().toString(), fundId, ORG, "FOOD", BigDecimal.ONE, "KGS",
                "CUST-1", "WH-1", allocationId, null, SYSTEM);
        return mongoTemplate.findOne(new Query(Criteria.where("eventType").is("ASSET_REGISTERED")
                .and("payload.allocationId").is(allocationId)), TraceabilityEventDocument.class).getStreamId();
    }

    private String registerCaminoB(String commandId, String campaignRef, HumanActor actor) {
        assets.registerPhysicalAssetFromDonation(commandId, ORG, "DONOR-1", "BLANKETS", BigDecimal.TEN, "UNITS",
                "CUST-1", "WH-1", campaignRef, actor);
        return mongoTemplate.findOne(new Query(Criteria.where("aggregateType").is("PhysicalAsset")),
                TraceabilityEventDocument.class).getStreamId();
    }

    private Throwable catchRejection(String campaignRef) {
        try {
            registerCaminoB(UUID.randomUUID().toString(), campaignRef, new HumanActor(EMPLOYEE));
        } catch (RuntimeException e) {
            return e;
        }
        throw new AssertionError("Se esperaba un rechazo para " + campaignRef);
    }

    private AssetRegisteredV3Payload genesis(String assetId) {
        List<DomainEventPayload> payloads = eventStore.loadStream(assetId).stream().map(e -> e.payload()).toList();
        assertThat(payloads.get(0)).isInstanceOf(AssetRegisteredV3Payload.class);
        return (AssetRegisteredV3Payload) payloads.get(0);
    }

    private String storedSchemaVersion(String assetId) {
        return mongoTemplate.findOne(new Query(Criteria.where("streamId").is(assetId)), TraceabilityEventDocument.class)
                .getSchemaVersion();
    }

    private Object storedPayloadField(String assetId, String field) {
        return mongoTemplate.findOne(new Query(Criteria.where("streamId").is(assetId)), TraceabilityEventDocument.class)
                .getPayload().get(field);
    }
}
