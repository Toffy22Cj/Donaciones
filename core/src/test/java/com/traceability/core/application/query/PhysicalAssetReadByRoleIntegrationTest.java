package com.traceability.core.application.query;

import com.traceability.contracts.HashPort;
import com.traceability.contracts.authorization.AuthorizationRole;
import com.traceability.core.application.authorization.CrossOrganizationAccessException;
import com.traceability.core.application.authorization.InsufficientRoleException;
import com.traceability.core.application.command.PhysicalAssetCommandService;
import com.traceability.core.application.exception.PhysicalAssetNotFoundException;
import com.traceability.core.application.port.out.EventStorePort;
import com.traceability.core.application.port.out.PhysicalAssetOperationalReadPort;
import com.traceability.core.application.port.out.PhysicalAssetOperationalView;
import com.traceability.core.domain.event.HumanActor;
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
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-032 Enmienda 1 (PROPUESTA; plan 1 de {@code plan-tanda-2026-11-19.md}, tests 1 a 6): la lectura del lote
 * ({@code findOperationalView}) admite a {@code ADMINISTRATOR}, {@code REPRESENTATIVE} y {@code EMPLOYEE} de la MISMA
 * organización. No abre ningún comando, ni {@code findSplitStatus}, ni la plataforma; otra organización, una cuenta sin
 * organización y un activo inexistente siguen recibiendo el mismo 403 (DD-01, DD-12).
 */
@SpringBootTest(properties = {
        "core.projection.retry.delay=99999999",
        "core.projection.retry.timeout-minutes=1",
        "saga.outbox.delay=99999999"
})
@Testcontainers
@Import(TestIdentityPrincipalPortConfig.class)
class PhysicalAssetReadByRoleIntegrationTest {

    private static final String ORG = "ORG-E1-032";
    private static final String OTHER_ORG = "ORG-E1-032-OTHER";
    private static final String EMPLOYEE = "acc-e1-employee";
    private static final String ADMIN_ONLY = "acc-e1-admin";
    private static final String REPRESENTATIVE = "acc-e1-representative";
    private static final String ADMIN_AND_EMPLOYEE = "acc-e1-admin-employee";
    private static final String OTHER_ADMIN = "acc-e1-other-admin";
    private static final String OTHER_REPRESENTATIVE = "acc-e1-other-representative";
    private static final String NO_ORGANIZATION = "acc-e1-no-org";
    private static final String PLATFORM = "acc-e1-platform";
    private static final String NO_ROLES = "acc-e1-no-roles";
    private static final String VALID_EVIDENCE = "sha256:" + "ab".repeat(32);

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
        identityPrincipalPort.addPrincipal(ADMIN_ONLY, ORG, Set.of(AuthorizationRole.ADMINISTRATOR));
        identityPrincipalPort.addPrincipal(REPRESENTATIVE, ORG, Set.of(AuthorizationRole.REPRESENTATIVE));
        identityPrincipalPort.addPrincipal(ADMIN_AND_EMPLOYEE, ORG, Set.of(AuthorizationRole.ADMINISTRATOR, AuthorizationRole.EMPLOYEE));
        identityPrincipalPort.addPrincipal(OTHER_ADMIN, OTHER_ORG, Set.of(AuthorizationRole.ADMINISTRATOR));
        identityPrincipalPort.addPrincipal(OTHER_REPRESENTATIVE, OTHER_ORG, Set.of(AuthorizationRole.REPRESENTATIVE));
        identityPrincipalPort.addPrincipal(NO_ORGANIZATION, null, Set.of(AuthorizationRole.ADMINISTRATOR));
        identityPrincipalPort.addPrincipal(PLATFORM, null, Set.of());
        identityPrincipalPort.addPrincipal(NO_ROLES, ORG, Set.of());
    }

    private static HumanActor actor(String accountId) {
        return new HumanActor(accountId);
    }

    private String register() {
        return assets.registerPhysicalAssetFromDonation(UUID.randomUUID().toString(), ORG, "anon:donor", "FOOD",
                new BigDecimal("10"), "KGS", "CUST-1", "WH-1", "05001", actor(EMPLOYEE)).assetId();
    }

    private long eventCount() {
        return mongoTemplate.count(new org.springframework.data.mongodb.core.query.Query(), TraceabilityEventDocument.class);
    }

    // --- Tests 1 y 2: los dos roles de la misma organización leen, con los mismos campos que el empleado ---

    @Test
    void administratorOfTheSameOrganization_readsTheAsset_withTheSameFieldsAsTheEmployee() {
        String assetId = register();

        PhysicalAssetOperationalView byEmployee = reads.findOperationalView(assetId, actor(EMPLOYEE));
        PhysicalAssetOperationalView byAdmin = reads.findOperationalView(assetId, actor(ADMIN_ONLY));

        assertThat(byAdmin).isEqualTo(byEmployee);
        assertThat(byAdmin.lifecycleStatus()).isEqualTo("REGISTERED");
    }

    @Test
    void representativeOfTheSameOrganization_readsTheAsset_withTheSameFieldsAsTheEmployee() {
        String assetId = register();

        assertThat(reads.findOperationalView(assetId, actor(REPRESENTATIVE)))
                .isEqualTo(reads.findOperationalView(assetId, actor(EMPLOYEE)));
        assertThat(reads.findOperationalView(assetId, actor(ADMIN_AND_EMPLOYEE)))
                .isEqualTo(reads.findOperationalView(assetId, actor(EMPLOYEE)));
    }

    // --- Test 3: la frontera de organización sigue igual; el mismo 403 ---

    @Test
    void otherOrganization_noOrganization_platform_andMissingAsset_stayForbidden() {
        String assetId = register();

        assertThatThrownBy(() -> reads.findOperationalView(assetId, actor(OTHER_ADMIN)))
                .isInstanceOf(CrossOrganizationAccessException.class);
        assertThatThrownBy(() -> reads.findOperationalView(assetId, actor(OTHER_REPRESENTATIVE)))
                .isInstanceOf(CrossOrganizationAccessException.class);
        assertThatThrownBy(() -> reads.findOperationalView(assetId, actor(NO_ORGANIZATION)))
                .isInstanceOf(CrossOrganizationAccessException.class);
        // test 6: un administrador de plataforma (sin organización) no entra por esta ruta
        assertThatThrownBy(() -> reads.findOperationalView(assetId, actor(PLATFORM)))
                .isInstanceOf(CrossOrganizationAccessException.class);
        // una cuenta de la organización sin ningún rol sigue sin leer
        assertThatThrownBy(() -> reads.findOperationalView(assetId, actor(NO_ROLES)))
                .isInstanceOf(InsufficientRoleException.class);
        // un activo inexistente: la misma excepción que hacia fuera responde como «otra organización» (DD-12)
        assertThatThrownBy(() -> reads.findOperationalView("no-existe-" + UUID.randomUUID(), actor(ADMIN_ONLY)))
                .isInstanceOf(PhysicalAssetNotFoundException.class);
    }

    // --- Test 4: abrir la lectura no abre ningún comando (never) ---

    @Test
    void openingTheRead_opensNoCommand_forAdministratorOnlyOrRepresentative() {
        String assetId = register();
        long events = eventCount();
        long claims = mongoTemplate.getCollection("processed_commands").countDocuments();

        for (String account : new String[] {ADMIN_ONLY, REPRESENTATIVE}) {
            HumanActor who = actor(account);
            assertThatThrownBy(() -> assets.registerPhysicalAssetFromDonation(UUID.randomUUID().toString(), ORG, "anon:donor",
                    "FOOD", BigDecimal.ONE, "KGS", "C", "L", "05001", who)).isInstanceOf(InsufficientRoleException.class);
            assertThatThrownBy(() -> assets.splitPhysicalAsset(UUID.randomUUID().toString(), assetId, BigDecimal.ONE, who))
                    .isInstanceOf(InsufficientRoleException.class);
            assertThatThrownBy(() -> assets.dispatchAsset(UUID.randomUUID().toString(), assetId, "C", who))
                    .isInstanceOf(InsufficientRoleException.class);
            assertThatThrownBy(() -> assets.receiveAsset(UUID.randomUUID().toString(), assetId, "WH", "05001", "R", who))
                    .isInstanceOf(InsufficientRoleException.class);
            assertThatThrownBy(() -> assets.deliverAsset(UUID.randomUUID().toString(), assetId, "C", "B", "L", "05001",
                    VALID_EVIDENCE, java.time.Instant.now(), who)).isInstanceOf(InsufficientRoleException.class);
        }

        // nada se escribió: ni eventos ni reclamos de comando
        assertThat(eventCount()).isEqualTo(events);
        assertThat(mongoTemplate.getCollection("processed_commands").countDocuments()).isEqualTo(claims);
        assertThat(eventStore.loadStream(assetId)).hasSize(1);
    }

    // --- Test 5: findSplitStatus SIGUE exigiendo EMPLOYEE (Q-F-2) ---

    @Test
    void splitStatus_stillRequiresTheEmployeeRole() {
        String assetId = register();
        String child = UUID.randomUUID().toString();

        assertThatThrownBy(() -> reads.findSplitStatus(assetId, child, actor(ADMIN_ONLY)))
                .isInstanceOf(InsufficientRoleException.class);
        assertThatThrownBy(() -> reads.findSplitStatus(assetId, child, actor(REPRESENTATIVE)))
                .isInstanceOf(InsufficientRoleException.class);
        assertThat(reads.findSplitStatus(assetId, child, actor(EMPLOYEE))).isEmpty();
    }

    // --- La lista de lotes no cambia (Q-F-3): el REPRESENTATIVE sigue sin ella ---

    @Test
    void listForOrganization_isUnchanged_representativeStillExcluded() {
        register();

        assertThat(reads.listForOrganization(ORG, actor(ADMIN_ONLY))).hasSize(1);
        assertThat(reads.listForOrganization(ORG, actor(EMPLOYEE))).hasSize(1);
        assertThatThrownBy(() -> reads.listForOrganization(ORG, actor(REPRESENTATIVE)))
                .isInstanceOf(InsufficientRoleException.class);
    }
}
