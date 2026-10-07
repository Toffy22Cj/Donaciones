package com.traceability.core.application.command;

import com.traceability.contracts.HashPort;
import com.traceability.contracts.authorization.AuthorizationRole;
import com.traceability.core.application.authorization.CrossOrganizationAccessException;
import com.traceability.core.application.authorization.InsufficientRoleException;
import com.traceability.core.application.exception.CommandIdReusedException;
import com.traceability.core.application.exception.PhysicalAssetNotFoundException;
import com.traceability.core.application.port.out.EventStorePort;
import com.traceability.core.application.port.out.PhysicalAssetOperationalReadPort;
import com.traceability.core.application.port.out.PhysicalAssetOperationalView;
import com.traceability.core.application.saga.SplitResolutionStatus;
import com.traceability.core.domain.event.DomainEvent;
import com.traceability.core.domain.event.HumanActor;
import com.traceability.core.domain.event.SystemActor;
import com.traceability.core.domain.fund.OrganizationRef;
import com.traceability.core.domain.physicalasset.AssetIds;
import com.traceability.core.domain.physicalasset.payloads.AssetDispatchedPayload;
import com.traceability.core.domain.physicalasset.payloads.AssetReceivedPayload;
import com.traceability.core.infrastructure.authorization.TestIdentityPrincipalPort;
import com.traceability.core.infrastructure.authorization.TestIdentityPrincipalPortConfig;
import com.traceability.core.infrastructure.persistence.mongo.TraceabilityEventDocument;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Plan B6-c, tests 1–4: lo que {@code core} añade para exponer los activos por HTTP. D-ASSET ({@code dispatchAsset},
 * {@code receiveAsset}), id determinista del registro (Q9), reenvío de un {@code commandId} de otro comando (DD-11),
 * activo inexistente (DD-12) y la lectura operacional (DD-13).
 */
@SpringBootTest(properties = {
        "core.projection.retry.delay=99999999",
        "core.projection.retry.timeout-minutes=1",
        "saga.outbox.delay=99999999"
})
@Testcontainers
@Import(TestIdentityPrincipalPortConfig.class)
class AssetHttpCommandsIntegrationTest {

    private static final String ORG = "ORG-B6C";
    private static final String OTHER_ORG = "ORG-B6C-OTHER";
    private static final String EMPLOYEE = "acc-b6c-employee";
    private static final String OTHER_EMPLOYEE = "acc-b6c-other-employee";
    private static final String ADMIN_ONLY = "acc-b6c-admin";
    private static final SystemActor SYSTEM = new SystemActor("b6c-tests");

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:6.0")).withCommand("--replSet", "rs0");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }

    @Configuration
    @SpringBootApplication(scanBasePackages = "com.traceability.core")
    @org.springframework.data.mongodb.repository.config.EnableMongoRepositories(basePackages = "com.traceability.core")
    static class TestConfig {}

    @SpyBean private TestIdentityPrincipalPort identityPrincipalPort;
    @MockBean private HashPort hashPort;

    @Autowired private PhysicalAssetCommandService assets;
    @Autowired private FundCommandService funds;
    @Autowired private PhysicalAssetOperationalReadPort reads;
    @Autowired private EventStorePort eventStore;
    @Autowired private MongoTemplate mongoTemplate;

    @BeforeEach
    void setUp() {
        mongoTemplate.dropCollection(TraceabilityEventDocument.class);
        mongoTemplate.dropCollection("processed_commands");
        mongoTemplate.dropCollection("outbox");
        identityPrincipalPort.reset();
        identityPrincipalPort.addPrincipal(EMPLOYEE, ORG, Set.of(AuthorizationRole.EMPLOYEE));
        identityPrincipalPort.addPrincipal(OTHER_EMPLOYEE, OTHER_ORG, Set.of(AuthorizationRole.EMPLOYEE));
        identityPrincipalPort.addPrincipal(ADMIN_ONLY, ORG, Set.of(AuthorizationRole.ADMINISTRATOR));
    }

    private static HumanActor actor(String accountId) {
        return new HumanActor(accountId);
    }

    private String registerInKind(String commandId) {
        return assets.registerPhysicalAssetFromDonation(commandId, ORG, "anon:donor", "FOOD", new BigDecimal("10"),
                "KGS", "CUST-1", "WH-1", actor(EMPLOYEE)).assetId();
    }

    private long eventCount() {
        return mongoTemplate.count(new org.springframework.data.mongodb.core.query.Query(), TraceabilityEventDocument.class);
    }

    // --- Test 2: id determinista del registro (Q9) ---

    @Test
    void registerFromDonation_returnsTheDeterministicId_andADuplicateReturnsTheSameResultWithoutWriting() {
        String commandId = UUID.randomUUID().toString();

        RegisteredAsset first = assets.registerPhysicalAssetFromDonation(commandId, ORG, "anon:donor", "FOOD",
                new BigDecimal("10"), "KGS", "CUST-1", "WH-1", actor(EMPLOYEE));
        long events = eventCount();
        RegisteredAsset again = assets.registerPhysicalAssetFromDonation(commandId, ORG, "anon:donor", "FOOD",
                new BigDecimal("99"), "KGS", "CUST-2", "WH-2", actor(EMPLOYEE));

        assertThat(first.assetId()).isEqualTo(AssetIds.of(ORG, commandId));
        assertThat(first.donationRef()).isNotBlank();
        assertThat(again).isEqualTo(first);
        assertThat(eventCount()).isEqualTo(events);
        assertThat(eventStore.loadStream(first.assetId())).hasSize(1);
    }

    @Test
    void registerPathA_returnsTheDeterministicIdAndTheCampaignOfTheFund() {
        String fundId = UUID.randomUUID().toString();
        String allocationId = UUID.randomUUID().toString();
        funds.clearFundsGenesis(UUID.randomUUID().toString(), fundId, new OrganizationRef(ORG), "CAMP-B6C", "anon:d",
                "COP", 1000L, "SRC", SYSTEM);
        funds.requestAllocation(UUID.randomUUID().toString(), fundId, allocationId, 100L, SYSTEM);
        String commandId = UUID.randomUUID().toString();

        RegisteredAsset first = assets.registerPhysicalAsset(commandId, fundId, ORG, "FOOD", BigDecimal.ONE, "KGS",
                "CUST-1", "WH-1", allocationId, null, actor(EMPLOYEE));
        RegisteredAsset again = assets.registerPhysicalAsset(commandId, fundId, ORG, "FOOD", BigDecimal.ONE, "KGS",
                "CUST-1", "WH-1", allocationId, null, actor(EMPLOYEE));

        assertThat(first).isEqualTo(new RegisteredAsset(AssetIds.of(ORG, commandId), null, "CAMP-B6C"));
        assertThat(again).isEqualTo(first);
    }

    @Test
    void theSameCommandIdInTwoOrganizations_givesDifferentAssets() {
        String commandId = UUID.randomUUID().toString();

        assertThat(AssetIds.of(ORG, commandId)).isNotEqualTo(AssetIds.of(OTHER_ORG, commandId));
        assertThat(AssetIds.of(ORG, commandId)).isEqualTo(AssetIds.of(ORG, commandId));
    }

    // --- Test 3: Command-Id de otro comando (DD-11) ---

    @Test
    void aCommandIdAlreadyUsedByAnotherCommand_isRejected_withoutWriting() {
        String commandId = UUID.randomUUID().toString();
        String assetId = registerInKind(commandId);
        long events = eventCount();

        assertThatThrownBy(() -> assets.dispatchAsset(commandId, assetId, "CARRIER-1", actor(EMPLOYEE)))
                .isInstanceOf(CommandIdReusedException.class);
        assertThatThrownBy(() -> assets.splitPhysicalAsset(commandId, assetId, BigDecimal.ONE, actor(EMPLOYEE)))
                .isInstanceOf(CommandIdReusedException.class);
        assertThatThrownBy(() -> assets.registerPhysicalAssetFromDonation(commandId, OTHER_ORG, "anon:x", "FOOD",
                BigDecimal.ONE, "KGS", "C", "L", actor(OTHER_EMPLOYEE)))
                .isInstanceOf(CommandIdReusedException.class);
        assertThat(eventCount()).isEqualTo(events);
    }

    @Test
    void aCommandIdClaimedWithoutOutcome_isNotTreatedAsADuplicate() {
        String assetId = registerInKind(UUID.randomUUID().toString());
        String legacy = UUID.randomUUID().toString();
        mongoTemplate.getCollection("processed_commands").insertOne(new org.bson.Document("_id", legacy)
                .append("processedAt", new java.util.Date()));

        assertThatThrownBy(() -> assets.dispatchAsset(legacy, assetId, "CARRIER-1", actor(EMPLOYEE)))
                .isInstanceOf(CommandIdReusedException.class);
    }

    // --- Test 1: D-ASSET ---

    @Test
    void dispatchThenReceive_writeTheirEvents_andAreIdempotent() {
        String assetId = registerInKind(UUID.randomUUID().toString());
        String dispatch = UUID.randomUUID().toString();
        String receive = UUID.randomUUID().toString();

        assets.dispatchAsset(dispatch, assetId, "CARRIER-1", actor(EMPLOYEE));
        assets.dispatchAsset(dispatch, assetId, "CARRIER-1", actor(EMPLOYEE));
        assets.receiveAsset(receive, assetId, "WH-2", "RECEIVER-1", actor(EMPLOYEE));
        assets.receiveAsset(receive, assetId, "WH-2", "RECEIVER-1", actor(EMPLOYEE));

        List<DomainEvent> stream = eventStore.loadStream(assetId);
        assertThat(stream).hasSize(3);
        assertThat(stream.get(1).payload()).isEqualTo(new AssetDispatchedPayload("CARRIER-1", "WH-1"));
        assertThat(stream.get(2).payload()).isEqualTo(new AssetReceivedPayload("WH-2", "RECEIVER-1"));
        assertThat(reads.findOperationalView(assetId, actor(EMPLOYEE)).lifecycleStatus()).isEqualTo("RECEIVED");
    }

    @Test
    void dispatch_requiresTheEmployeeRole_andTheOrganization_withoutWriting() {
        String assetId = registerInKind(UUID.randomUUID().toString());

        assertThatThrownBy(() -> assets.dispatchAsset(UUID.randomUUID().toString(), assetId, "C", actor(ADMIN_ONLY)))
                .isInstanceOf(InsufficientRoleException.class);
        assertThatThrownBy(() -> assets.dispatchAsset(UUID.randomUUID().toString(), assetId, "C", actor(OTHER_EMPLOYEE)))
                .isInstanceOf(CrossOrganizationAccessException.class);
        assertThatThrownBy(() -> assets.receiveAsset(UUID.randomUUID().toString(), assetId, "WH", "R", actor(OTHER_EMPLOYEE)))
                .isInstanceOf(CrossOrganizationAccessException.class);
        assertThat(eventStore.loadStream(assetId)).hasSize(1);
        assertThat(mongoTemplate.getCollection("processed_commands").countDocuments()).isEqualTo(1);
    }

    @Test
    void anAssetThatDoesNotExist_isANamedFailure_inEveryCommandAndRead() {
        String missing = UUID.randomUUID().toString();

        assertThatThrownBy(() -> assets.dispatchAsset(UUID.randomUUID().toString(), missing, "C", actor(EMPLOYEE)))
                .isInstanceOf(PhysicalAssetNotFoundException.class);
        assertThatThrownBy(() -> assets.receiveAsset(UUID.randomUUID().toString(), missing, "W", "R", actor(EMPLOYEE)))
                .isInstanceOf(PhysicalAssetNotFoundException.class);
        assertThatThrownBy(() -> assets.deliverAsset(UUID.randomUUID().toString(), missing, "C", "B", "L", "E",
                java.time.Instant.now(), actor(EMPLOYEE))).isInstanceOf(PhysicalAssetNotFoundException.class);
        assertThatThrownBy(() -> assets.splitPhysicalAsset(UUID.randomUUID().toString(), missing, BigDecimal.ONE,
                actor(EMPLOYEE))).isInstanceOf(PhysicalAssetNotFoundException.class);
        assertThatThrownBy(() -> reads.findOperationalView(missing, actor(EMPLOYEE)))
                .isInstanceOf(PhysicalAssetNotFoundException.class);
        assertThatThrownBy(() -> reads.findSplitStatus(missing, UUID.randomUUID().toString(), actor(EMPLOYEE)))
                .isInstanceOf(PhysicalAssetNotFoundException.class);
        assertThat(eventCount()).isZero();
    }

    // --- Test 4: lectura operacional (matriz §4b) ---

    @Test
    void operationalView_hasExactlyTheOperationalFields() {
        String assetId = registerInKind(UUID.randomUUID().toString());

        PhysicalAssetOperationalView view = reads.findOperationalView(assetId, actor(EMPLOYEE));

        assertThat(view).isEqualTo(new PhysicalAssetOperationalView(assetId, "REGISTERED", "CUST-1", "WH-1",
                new BigDecimal("10.0000"), "KGS", null));
        assertThat(PhysicalAssetOperationalView.class.getRecordComponents()).extracting(c -> c.getName())
                .containsExactly("assetRef", "lifecycleStatus", "currentCustodianRef", "currentLocation", "quantity",
                        "unitOfMeasure", "campaignRef");
    }

    @Test
    void reads_requireTheEmployeeRoleAndTheOrganization() {
        String assetId = registerInKind(UUID.randomUUID().toString());

        assertThatThrownBy(() -> reads.findOperationalView(assetId, actor(ADMIN_ONLY)))
                .isInstanceOf(InsufficientRoleException.class);
        assertThatThrownBy(() -> reads.findOperationalView(assetId, actor(OTHER_EMPLOYEE)))
                .isInstanceOf(CrossOrganizationAccessException.class);
        assertThatThrownBy(() -> reads.findSplitStatus(assetId, UUID.randomUUID().toString(), actor(OTHER_EMPLOYEE)))
                .isInstanceOf(CrossOrganizationAccessException.class);
    }

    @Test
    void splitStatus_isPendingUntilTheSagaRuns_andEmptyForAnUnknownChild() {
        String assetId = registerInKind(UUID.randomUUID().toString());
        String child = assets.splitPhysicalAsset(UUID.randomUUID().toString(), assetId, new BigDecimal("4"), actor(EMPLOYEE));

        assertThat(reads.findSplitStatus(assetId, child, actor(EMPLOYEE))).contains(SplitResolutionStatus.PENDING);
        assertThat(reads.findSplitStatus(assetId, UUID.randomUUID().toString(), actor(EMPLOYEE))).isEqualTo(Optional.empty());
    }
}
