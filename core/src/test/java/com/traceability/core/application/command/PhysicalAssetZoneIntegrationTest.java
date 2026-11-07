package com.traceability.core.application.command;

import com.traceability.contracts.HashPort;
import com.traceability.contracts.authorization.AuthorizationRole;
import com.traceability.core.application.exception.UnknownMunicipalityCodeException;
import com.traceability.core.application.port.out.EventStorePort;
import com.traceability.core.domain.event.DomainEvent;
import com.traceability.core.domain.event.DomainEventPayload;
import com.traceability.core.domain.event.HumanActor;
import com.traceability.core.domain.event.SystemActor;
import com.traceability.core.domain.fund.OrganizationRef;
import com.traceability.core.domain.physicalasset.payloads.AssetDeliveredV2Payload;
import com.traceability.core.domain.physicalasset.payloads.AssetDispatchedV2Payload;
import com.traceability.core.domain.physicalasset.payloads.AssetReceivedV2Payload;
import com.traceability.core.domain.physicalasset.payloads.AssetRegisteredV4Payload;
import com.traceability.core.infrastructure.authorization.TestIdentityPrincipalPort;
import com.traceability.core.infrastructure.authorization.TestIdentityPrincipalPortConfig;
import com.traceability.core.infrastructure.persistence.mongo.TraceabilityEventDocument;
import com.traceability.core.support.StubMunicipalityCatalogPort;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Enmienda 2 de ADR-029, D3 (plan de la zona, tests 4, 5 y 12): el código de municipio es obligatorio al registrar (los
 * dos caminos), recibir y entregar, y lo valida {@code core} con {@code MunicipalityCatalogPort} después de autorizar y
 * antes de persistir: un código ausente o desconocido no escribe ningún evento ni reclama el {@code Command-Id}.
 */
@SpringBootTest(properties = {
        "core.projection.retry.delay=99999999",
        "core.projection.retry.timeout-minutes=1"
})
@Testcontainers
@Import(TestIdentityPrincipalPortConfig.class)
class PhysicalAssetZoneIntegrationTest {

    private static final String ORG = "ORG-ZONE";
    private static final String EMPLOYEE = "acc-zone-employee";
    private static final String OTHER_EMPLOYEE = "acc-zone-other";
    private static final SystemActor SYSTEM = new SystemActor("zone-tests");
    private static final String EVIDENCE = "sha256:" + "c".repeat(64);

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
    @Autowired private StubMunicipalityCatalogPort catalog;
    @Autowired private MongoTemplate mongoTemplate;

    @BeforeEach
    void setup() {
        clean();
        identityPrincipalPort.reset();
        identityPrincipalPort.addPrincipal(EMPLOYEE, ORG, Set.of(AuthorizationRole.EMPLOYEE));
        identityPrincipalPort.addPrincipal(OTHER_EMPLOYEE, "ORG-ZONE-OTHER", Set.of(AuthorizationRole.EMPLOYEE));
        catalog.reset();
        when(hashPort.canonicalizeAndHash(any(), any())).thenAnswer(inv -> UUID.randomUUID().toString());
    }

    @AfterEach
    void clean() {
        mongoTemplate.dropCollection(TraceabilityEventDocument.class);
        mongoTemplate.dropCollection("processed_commands");
    }

    private long events() {
        return mongoTemplate.count(new Query(Criteria.where("aggregateType").is("PhysicalAsset")), TraceabilityEventDocument.class);
    }

    private long claims() {
        return mongoTemplate.getCollection("processed_commands").countDocuments();
    }

    private String fund() {
        String fundId = UUID.randomUUID().toString();
        funds.clearFundsGenesis(UUID.randomUUID().toString(), fundId, new OrganizationRef(ORG), null, "DONOR-1", "COP",
                1000L, "SRC-1", SYSTEM);
        return fundId;
    }

    private String registerA(String municipalityCode) {
        return assets.registerPhysicalAsset(UUID.randomUUID().toString(), fund(), ORG, "FOOD", BigDecimal.TEN, "KGS",
                "CUST-1", "Bodega norte", municipalityCode, UUID.randomUUID().toString(), null, SYSTEM).assetId();
    }

    private List<DomainEventPayload> payloads(String assetId) {
        return eventStore.loadStream(assetId).stream().map(DomainEvent::payload).toList();
    }

    // test 4: los cuatro comandos con municipio, sin él o con uno desconocido → la excepción nombrada y ningún evento
    @Test
    void test4_withoutOrWithAnUnknownMunicipality_theFourCommandsFail_andWriteNothing() {
        String fundId = fund();
        long before = events();
        long claimsBefore = claims();
        for (String code : new String[] {null, "99999"}) {
            assertThatThrownBy(() -> assets.registerPhysicalAsset(UUID.randomUUID().toString(), fundId, ORG, "FOOD",
                    BigDecimal.ONE, "KGS", "CUST-1", "Bodega", code, UUID.randomUUID().toString(), null, SYSTEM))
                    .isInstanceOf(UnknownMunicipalityCodeException.class);
            assertThatThrownBy(() -> assets.registerPhysicalAssetFromDonation(UUID.randomUUID().toString(), ORG,
                    "DONOR-1", "BLANKETS", BigDecimal.TEN, "UNITS", "CUST-1", "Bodega", code, null,
                    new HumanActor(EMPLOYEE)))
                    .isInstanceOf(UnknownMunicipalityCodeException.class);
        }
        assertThat(events()).as("ningún registro").isEqualTo(before);
        assertThat(claims()).as("ningún Command-Id reclamado").isEqualTo(claimsBefore);

        String assetId = registerA("05001");
        assets.dispatchAsset(UUID.randomUUID().toString(), assetId, "CARRIER-1", SYSTEM);
        long written = events();
        long claimed = claims();
        for (String code : new String[] {null, "99999"}) {
            assertThatThrownBy(() -> assets.receiveAsset(UUID.randomUUID().toString(), assetId, "Centro", code, "REC-1",
                    SYSTEM)).isInstanceOf(UnknownMunicipalityCodeException.class);
            assertThatThrownBy(() -> assets.deliverAsset(UUID.randomUUID().toString(), assetId, "CUST-F", "BEN-1",
                    "Escuela", code, EVIDENCE, Instant.now(), SYSTEM)).isInstanceOf(UnknownMunicipalityCodeException.class);
        }
        assertThat(events()).as("ni recepción ni entrega").isEqualTo(written);
        assertThat(claims()).isEqualTo(claimed);
    }

    // autorizar primero: otra organización recibe el rechazo de autorización y no llega a consultar el catálogo
    @Test
    void anActorOfAnotherOrganization_isRejectedBeforeTheCatalogIsQueried() {
        assertThatThrownBy(() -> assets.registerPhysicalAssetFromDonation(UUID.randomUUID().toString(), ORG, "DONOR-1",
                "BLANKETS", BigDecimal.TEN, "UNITS", "CUST-1", "Bodega", "05001", null, new HumanActor(OTHER_EMPLOYEE)))
                .isNotInstanceOf(UnknownMunicipalityCodeException.class);
        assertThat(catalog.queries()).isEmpty();
    }

    // tests 5 y 12: cada paso sella su zona; el despacho copia la del paso anterior; un cambio posterior del catálogo
    // no cambia lo sellado (el despacho copia del estado, no consulta el catálogo)
    @Test
    void test5and12_eachStepSealsItsZone_theDispatchCopiesThePreviousOne_andTheSealedZoneBeatsTheCatalog() {
        String assetId = registerA("05001");
        catalog.rename("05001", "MEDELLÍN RENOMBRADO", "DIVIPOLA-OTRA");

        assets.dispatchAsset(UUID.randomUUID().toString(), assetId, "CARRIER-1", SYSTEM);
        assets.receiveAsset(UUID.randomUUID().toString(), assetId, "Centro de acopio", "05002", "REC-1", SYSTEM);
        assets.deliverAsset(UUID.randomUUID().toString(), assetId, "CUST-F", "BEN-1", "Escuela", "11001", EVIDENCE,
                Instant.parse("2026-11-02T12:00:00Z"), SYSTEM);

        List<DomainEventPayload> p = payloads(assetId);
        AssetRegisteredV4Payload registered = (AssetRegisteredV4Payload) p.get(0);
        assertThat(registered.zoneName()).isEqualTo("MEDELLÍN, ANTIOQUIA");
        assertThat(registered.catalogVersion()).isEqualTo(StubMunicipalityCatalogPort.VERSION);
        AssetDispatchedV2Payload dispatched = (AssetDispatchedV2Payload) p.get(1);
        assertThat(dispatched.zoneName()).as("lo sellado, no el catálogo de hoy").isEqualTo("MEDELLÍN, ANTIOQUIA");
        assertThat(dispatched.catalogVersion()).isEqualTo(StubMunicipalityCatalogPort.VERSION);
        AssetReceivedV2Payload received = (AssetReceivedV2Payload) p.get(2);
        assertThat(received.municipalityCode()).isEqualTo("05002");
        assertThat(received.zoneName()).isEqualTo("ABEJORRAL, ANTIOQUIA");
        AssetDeliveredV2Payload delivered = (AssetDeliveredV2Payload) p.get(3);
        assertThat(delivered.zoneName()).isEqualTo("BOGOTÁ, D.C.");
        assertThat(catalog.queries()).as("el despacho no consulta el catálogo").containsExactly("05001", "05002", "11001");
    }

    // el Camino B también sella la zona
    @Test
    void pathB_sealsTheZoneToo() {
        assets.registerPhysicalAssetFromDonation(UUID.randomUUID().toString(), ORG, "DONOR-1", "BLANKETS",
                BigDecimal.TEN, "UNITS", "CUST-1", "Bodega", "08001", null, new HumanActor(EMPLOYEE));
        String assetId = mongoTemplate.findOne(new Query(Criteria.where("aggregateType").is("PhysicalAsset")),
                TraceabilityEventDocument.class).getStreamId();
        AssetRegisteredV4Payload genesis = (AssetRegisteredV4Payload) payloads(assetId).get(0);
        assertThat(genesis.municipalityCode()).isEqualTo("08001");
        assertThat(genesis.zoneName()).isEqualTo("BARRANQUILLA, ATLÁNTICO");
    }
}
