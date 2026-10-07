package com.traceability.core.application.query;

import com.traceability.contracts.HashPort;
import com.traceability.contracts.authorization.AuthorizationRole;
import com.traceability.contracts.campaign.InKindEligibility;
import com.traceability.core.application.command.FundCommandService;
import com.traceability.core.application.command.PhysicalAssetCommandService;
import com.traceability.core.domain.event.HumanActor;
import com.traceability.core.domain.event.SystemActor;
import com.traceability.core.domain.fund.OrganizationRef;
import com.traceability.core.infrastructure.authorization.TestIdentityPrincipalPort;
import com.traceability.core.infrastructure.authorization.TestIdentityPrincipalPortConfig;
import com.traceability.core.infrastructure.persistence.mongo.TraceabilityEventDocument;
import com.traceability.core.support.StubCampaignInKindEligibilityPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Plan B5 (DD-33): unidades entregadas y receptores distintos de una convocatoria, leídos del event store por la
 * génesis 3.0 de cada activo. Incluye el Camino A, el Camino B y los hijos de una división.
 */
@SpringBootTest(properties = {
        "core.projection.retry.delay=99999999",
        "core.projection.retry.timeout-minutes=1"
})
@Testcontainers
@Import(TestIdentityPrincipalPortConfig.class)
class CampaignDeliveryFactsQueryIntegrationTest {

    private static final String ORG = "ORG-B5";
    private static final String EMPLOYEE = "acc-b5-employee";
    private static final SystemActor SYSTEM = new SystemActor("b5-tests");

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
    @Autowired private StubCampaignInKindEligibilityPort campaigns;
    @Autowired private MongoTemplate mongoTemplate;
    @Autowired private CampaignDeliveryFactsQuery query;

    @BeforeEach
    void setup() {
        mongoTemplate.dropCollection(TraceabilityEventDocument.class);
        mongoTemplate.dropCollection("processed_commands");
        identityPrincipalPort.reset();
        identityPrincipalPort.addPrincipal(EMPLOYEE, ORG, Set.of(AuthorizationRole.EMPLOYEE));
        campaigns.reset();
        campaigns.answer("CAMP-1", InKindEligibility.ELIGIBLE);
        campaigns.answer("CAMP-2", InKindEligibility.ELIGIBLE);
        when(hashPort.canonicalizeAndHash(any(), any())).thenAnswer(inv -> UUID.randomUUID().toString());
    }

    @AfterEach
    void clean() {
        mongoTemplate.dropCollection(TraceabilityEventDocument.class);
        mongoTemplate.dropCollection("processed_commands");
    }

    @Test
    void sumsDeliveredUnits_ofCaminoA_caminoB_andSplitChildren_andCountsDistinctRecipients() {
        // Camino A: 10 unidades; se dividen 3 en un hijo, que hereda CAMP-1. Padre (7) a BEN-1, hijo (3) a BEN-2.
        String parent = registerCaminoA("CAMP-1", "10");
        String child = assets.splitPhysicalAsset(UUID.randomUUID().toString(), parent, new BigDecimal("3"),
                new HumanActor(EMPLOYEE));
        assets.createSplitChild(parent, child);
        deliver(parent, "BEN-1");
        deliver(child, "BEN-2");
        // Camino B: 5 unidades a BEN-1 (receptor repetido).
        deliver(registerCaminoB("CAMP-1", "5"), "BEN-1");

        // Fuera: no entregado, otra convocatoria y sin convocatoria.
        dispatch(registerCaminoB("CAMP-1", "100"));
        deliver(registerCaminoB("CAMP-2", "100"), "BEN-3");
        deliver(registerCaminoB(null, "100"), "BEN-4");

        CampaignDeliveryFactsQuery.DeliveryFacts facts = query.deliveriesOf("CAMP-1");

        assertThat(facts.unitsDelivered()).isEqualByComparingTo("15");
        assertThat(facts.distinctRecipients()).isEqualTo(2);
        assertThat(facts.readAt()).isNotNull();
    }

    @Test
    void campaignWithoutAssets_hasZeroFacts() {
        CampaignDeliveryFactsQuery.DeliveryFacts facts = query.deliveriesOf("CAMP-EMPTY");

        assertThat(facts.unitsDelivered()).isEqualByComparingTo("0");
        assertThat(facts.distinctRecipients()).isZero();
    }

    // --- utilidades ---

    private String registerCaminoA(String fundCampaignRef, String quantity) {
        String fundId = UUID.randomUUID().toString();
        String allocationId = UUID.randomUUID().toString();
        funds.clearFundsGenesis(UUID.randomUUID().toString(), fundId, new OrganizationRef(ORG), fundCampaignRef,
                "DONOR-1", "COP", 1000L, "SRC-1", SYSTEM);
        assets.registerPhysicalAsset(UUID.randomUUID().toString(), fundId, ORG, "FOOD", new BigDecimal(quantity), "UNITS",
                "CUST-1", "WH-1", allocationId, null, SYSTEM);
        return mongoTemplate.findOne(new Query(Criteria.where("eventType").is("ASSET_REGISTERED")
                .and("payload.allocationId").is(allocationId)), TraceabilityEventDocument.class).getStreamId();
    }

    private String registerCaminoB(String campaignRef, String quantity) {
        return assets.registerPhysicalAssetFromDonation(UUID.randomUUID().toString(), ORG, "DONOR-1", "BLANKETS",
                new BigDecimal(quantity), "UNITS", "CUST-1", "WH-1", campaignRef, new HumanActor(EMPLOYEE)).assetId();
    }

    private void dispatch(String assetId) {
        assets.dispatchAsset(UUID.randomUUID().toString(), assetId, "CARRIER-1", new HumanActor(EMPLOYEE));
    }

    private void deliver(String assetId, String beneficiaryRef) {
        dispatch(assetId);
        assets.deliverAsset(UUID.randomUUID().toString(), assetId, "FINAL-CUST", beneficiaryRef, "LOC-FINAL",
                "EVIDENCE", Instant.parse("2027-01-10T10:00:00Z"), new HumanActor(EMPLOYEE));
    }
}
