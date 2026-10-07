package com.traceability.core.application.projection;

import com.traceability.contracts.HashPort;
import com.traceability.contracts.authorization.IdentityPrincipalPort;
import com.traceability.core.application.command.FundCommandService;
import com.traceability.core.application.command.PhysicalAssetCommandService;
import com.traceability.core.application.port.out.DonationReadModel;
import com.traceability.core.application.port.out.DonationReadPort;
import com.traceability.core.application.port.out.EventStorePort;
import com.traceability.core.application.service.TransactionalEventPublisher;
import com.traceability.core.domain.event.DomainEvent;
import com.traceability.core.domain.event.DomainEventPayload;
import com.traceability.core.domain.event.SystemActor;
import com.traceability.core.domain.fund.OrganizationRef;
import com.traceability.core.domain.physicalasset.PhysicalAsset;
import com.traceability.core.domain.physicalasset.payloads.AssetRegisteredV3Payload;
import com.traceability.core.infrastructure.persistence.mongo.TraceabilityEventDocument;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * B-PROJ (plan-b-proj.md): comandos reales → event store → change stream real → proyecciones. Antes de la
 * corrección ningún stream escrito por los comandos se proyectaba: el event store numera la génesis como 1 y los
 * manejadores la esperaban en 0, y además no reconocían los payloads v2.
 * <p>
 * Propiedades propias para no compartir contexto con {@code DonationProjectionIntegrationTest}, que inserta en
 * {@code event_store} con el change stream activo. Usa la configuración de arranque que encuentra la búsqueda de
 * Spring Boot en el paquete (la anidada de ese test, con el mismo escaneo de {@code core} y {@code @EnableScheduling}):
 * declarar otra aquí rompería esa búsqueda en los tests vecinos.
 */
@SpringBootTest(properties = {
        "core.projection.retry.delay=500",
        "core.projection.retry.timeout-minutes=5",
        "b-proj.e2e=true"
})
@Testcontainers
class ProjectionChangeStreamE2ETest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final SystemActor ACTOR = new SystemActor("b-proj-e2e");
    private static final String ORG = "org-b-proj";

    @Container
    static MongoDBContainer mongoDBContainer = new MongoDBContainer(DockerImageName.parse("mongo:6.0"))
            .withCommand("--replSet", "rs0");

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongoDBContainer::getReplicaSetUrl);
    }

    @MockBean private IdentityPrincipalPort identityPrincipalPort;
    @MockBean private HashPort hashPort;

    @Autowired private FundCommandService funds;
    @Autowired private PhysicalAssetCommandService assets;
    @Autowired private EventStorePort eventStore;
    @Autowired private TransactionalEventPublisher publisher;
    @Autowired private DonationReadPort donationRead;
    @Autowired private MongoTemplate mongoTemplate;

    @BeforeEach
    void stubHash() {
        when(hashPort.canonicalizeAndHash(any(), any())).thenAnswer(inv -> UUID.randomUUID().toString());
    }

    @Test
    void clearFundsGenesis_isProjectedWithAmountsCurrencyAndCampaign() {
        String fundId = UUID.randomUUID().toString();

        funds.clearFundsGenesis(cmd(), fundId, new OrganizationRef(ORG), "CAMP-1", "DONOR-1", "COP", 1500L,
                "SRC-1", ACTOR);

        DonationReadModel model = await(() -> donationRead.findByFundId(fundId));
        assertThat(model.originalAmount()).isEqualTo(1500L);
        assertThat(model.clearedAmount()).isEqualTo(1500L);
        assertThat(model.currency()).isEqualTo("COP");
        assertThat(model.campaignRef()).isEqualTo("CAMP-1");
        await(() -> Optional.ofNullable(mongoTemplate.findById(fundId, Document.class, "donation_audit_facts")));
        assertNoQuarantine(fundId);
    }

    @Test
    void laterClearing_doesNotOverwriteOriginalAmount() {
        String fundId = UUID.randomUUID().toString();
        funds.registerFund(cmd(), fundId, new OrganizationRef(ORG), "CAMP-1", "DONOR-1", "COP", 1000L, ACTOR);
        funds.clearFundsForPledge(cmd(), fundId, 400L, "SRC-1", ACTOR);
        funds.clearFundsForPledge(cmd(), fundId, 600L, "SRC-2", ACTOR);

        DonationReadModel model = await(() -> donationRead.findByFundId(fundId)
                .filter(m -> m.clearedAmount() == 1000L));
        assertThat(model.originalAmount()).isEqualTo(1000L);
        assertThat(model.campaignRef()).isEqualTo("CAMP-1");
        assertNoQuarantine(fundId);
    }

    @Test
    void caminoA_assetIsProjectedThroughItsLifecycle() {
        String fundId = UUID.randomUUID().toString();
        String allocationId = UUID.randomUUID().toString();
        funds.clearFundsGenesis(cmd(), fundId, new OrganizationRef(ORG), "CAMP-1", "DONOR-1", "COP", 1000L,
                "SRC-1", ACTOR);
        funds.requestAllocation(cmd(), fundId, allocationId, 400L, ACTOR);
        await(() -> donationRead.findByFundId(fundId).filter(m -> m.pendingAllocationAmount() == 400L));

        assets.registerPhysicalAsset(cmd(), fundId, ORG, "FOOD_RATION", BigDecimal.TEN, "KGS", "CUST-1", "WH-1",
                allocationId, null, ACTOR);
        String assetId = assetIdOfAllocation(allocationId);

        // asset_index se escribe antes que logistics y asset_history: se espera al último paso del registro.
        await(() -> Optional.ofNullable(mongoTemplate.findById(assetId, Document.class, "asset_history")));
        assertThat(mongoTemplate.findById(assetId, Document.class, "asset_index")).isNotNull();
        assertThat(logisticsStatus(fundId, assetId)).isEqualTo("REGISTERED");
        assertThat(historyStatuses(assetId)).containsExactly("REGISTERED");
        // D-CAMPAIGN (D7): el campaignRef por activo sale del payload v3, heredado del Fund (D2).
        assertThat(logisticsField(fundId, assetId, "campaignRef")).isEqualTo("CAMP-1");

        // D-ASSET: PhysicalAssetCommandService todavía no expone dispatch/receive. Se escriben con el agregado y
        // TransactionalEventPublisher; cuando D-ASSET añada los métodos del servicio, este test pasará a usarlos.
        appendWithAggregate(assetId, asset -> asset.dispatch("CARRIER-1"));
        appendWithAggregate(assetId, asset -> asset.receive("WH-2", "RECEIVER-1"));
        assets.deliverAsset(cmd(), assetId, "CUST-2", "BENEFICIARY-1", "WH-2", "EVIDENCE-1", Instant.now(), ACTOR);

        await(() -> Optional.of(historyStatuses(assetId)).filter(h -> h.size() == 4));
        assertThat(logisticsStatus(fundId, assetId)).isEqualTo("DELIVERED");
        assertThat(historyStatuses(assetId)).containsExactly("REGISTERED", "DISPATCHED", "RECEIVED", "DELIVERED");
        assertThat(donationRead.findByFundId(fundId).orElseThrow().logistics()).hasSize(1);
        assertNoQuarantine(fundId);
        assertNoQuarantine(assetId);
    }

    @Test
    void caminoA_registration_alwaysCarriesNullDonationRef() {
        String fundId = UUID.randomUUID().toString();
        String allocationId = UUID.randomUUID().toString();
        funds.clearFundsGenesis(cmd(), fundId, new OrganizationRef(ORG), null, "DONOR-1", "COP", 1000L, "SRC-1",
                ACTOR);
        funds.requestAllocation(cmd(), fundId, allocationId, 100L, ACTOR);

        assets.registerPhysicalAsset(cmd(), fundId, ORG, "FOOD_RATION", BigDecimal.ONE, "KGS", "CUST-1", "WH-1",
                allocationId, null, ACTOR);

        // Premisa de la detección del Camino B (donationRef != null): el Camino A nunca la cumple.
        DomainEventPayload genesis = eventStore.loadStream(assetIdOfAllocation(allocationId)).get(0).payload();
        assertThat(genesis).isInstanceOf(AssetRegisteredV3Payload.class);
        assertThat(((AssetRegisteredV3Payload) genesis).donationRef()).isNull();
    }

    @Test
    void caminoB_assetIsIgnoredExplicitly_withoutQuarantineNorOrphanDocuments() throws Exception {
        String fundId = UUID.randomUUID().toString();
        funds.clearFundsGenesis(cmd(), fundId, new OrganizationRef(ORG), "CAMP-1", "DONOR-1", "COP", 100L,
                "SRC-1", ACTOR);

        assets.registerPhysicalAssetFromDonation(cmd(), ORG, "DONOR-2", "BLANKETS", BigDecimal.TEN, "UNITS",
                "CUST-1", "WH-1", ACTOR);
        String assetId = mongoTemplate.findOne(new Query(Criteria.where("aggregateType").is("PhysicalAsset")
                        .and("eventType").is("ASSET_REGISTERED").and("payload.donorRef").is("DONOR-2")),
                TraceabilityEventDocument.class).getStreamId();
        appendWithAggregate(assetId, asset -> asset.dispatch("CARRIER-1"));

        // Marcador: un evento de fondos posterior ya proyectado garantiza que el change stream pasó por los del activo.
        funds.requestAllocation(cmd(), fundId, UUID.randomUUID().toString(), 50L, ACTOR);
        await(() -> donationRead.findByFundId(fundId).filter(m -> m.pendingAllocationAmount() == 50L));
        Thread.sleep(1500); // por encima de core.projection.retry.delay: un reintento ya se habría registrado

        assertThat(mongoTemplate.findById(assetId, Document.class, "asset_index")).isNull();
        assertThat(mongoTemplate.count(new Query(Criteria.where("_id").is(null)), "donation_audit_facts"))
                .as("ningún documento de auditoría con clave nula").isZero();
        assertThat(mongoTemplate.count(new Query(Criteria.where("auditMetadata.assetLastProcessedSequences." + assetId)
                .exists(true)), "donation_audit_facts")).as("ningún documento de auditoría registra el activo").isZero();
        assertThat(mongoTemplate.count(new Query(Criteria.where("logistics.assetId").is(assetId)), "donation_projections"))
                .as("el activo no se adjunta a ninguna donación").isZero();
        assertNoQuarantine(assetId);
    }

    // --- utilidades ---

    private static String cmd() {
        return UUID.randomUUID().toString();
    }

    private interface AssetCommand {
        void apply(PhysicalAsset asset);
    }

    private void appendWithAggregate(String assetId, AssetCommand command) {
        List<DomainEvent> history = eventStore.loadStream(assetId);
        List<DomainEventPayload> payloads = history.stream().map(DomainEvent::payload).toList();
        PhysicalAsset asset = PhysicalAsset.rehydrate(assetId, payloads, history.size());
        command.apply(asset);
        publisher.appendAndOutbox(assetId, "PhysicalAsset", history.size(), asset.getUncommittedEvents(), ACTOR,
                List.of(), cmd());
    }

    private String assetIdOfAllocation(String allocationId) {
        return mongoTemplate.findOne(new Query(Criteria.where("eventType").is("ASSET_REGISTERED")
                .and("payload.allocationId").is(allocationId)), TraceabilityEventDocument.class).getStreamId();
    }

    @SuppressWarnings("unchecked")
    private String logisticsStatus(String fundId, String assetId) {
        Document projection = mongoTemplate.findById(fundId, Document.class, "donation_projections");
        if (projection == null) {
            return null;
        }
        return ((List<Document>) projection.get("logistics", List.class)).stream()
                .filter(l -> assetId.equals(l.getString("assetId")))
                .map(l -> l.getString("lifecycleStatus"))
                .findFirst().orElse(null);
    }

    @SuppressWarnings("unchecked")
    private String logisticsField(String fundId, String assetId, String field) {
        Document projection = mongoTemplate.findById(fundId, Document.class, "donation_projections");
        return ((List<Document>) projection.get("logistics", List.class)).stream()
                .filter(l -> assetId.equals(l.getString("assetId")))
                .map(l -> l.getString(field))
                .findFirst().orElse(null);
    }

    @SuppressWarnings("unchecked")
    private List<String> historyStatuses(String assetId) {
        Document history = mongoTemplate.findById(assetId, Document.class, "asset_history");
        assertThat(history).as("asset_history de " + assetId).isNotNull();
        return ((List<Document>) history.get("transitions", List.class)).stream()
                .map(t -> t.getString("status")).toList();
    }

    private void assertNoQuarantine(String streamId) {
        assertThat(mongoTemplate.count(new Query(Criteria.where("streamId").is(streamId)), "quarantined_projections"))
                .as("reintentos o cuarentena de " + streamId).isZero();
    }

    private static <T> T await(Supplier<Optional<T>> probe) {
        Instant deadline = Instant.now().plus(TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            Optional<T> value = probe.get();
            if (value.isPresent()) {
                return value.get();
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("La proyección no llegó en " + TIMEOUT);
    }
}
