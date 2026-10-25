package com.traceability.app.application.integrity;

import com.traceability.app.application.integrity.OrganizationIntegrityQuery.OrganizationIntegrity;
import com.traceability.app.config.TraceabilityInfrastructureConfig;
import com.traceability.contracts.SequenceRange;
import com.traceability.contracts.authorization.AuthorizationPrincipal;
import com.traceability.contracts.authorization.AuthorizationRole;
import com.traceability.core.application.authorization.OrganizationBoundaryPolicy;
import com.traceability.core.infrastructure.persistence.mongo.TraceabilityEventDocument;
import com.traceability.crypto.application.port.out.MerkleBatchRepositoryPort;
import com.traceability.crypto.domain.AnchorStatus;
import com.traceability.crypto.domain.MerkleBatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ficha R-6, G-7 (plan D, §2.1; tests 10a, 10b y 10c) con Mongo real: los streams de la organización salen de sus
 * génesis (directorios), los recuentos de una agregación por lote, y cada lote se clasifica con la tabla del Bloque C
 * (P-R6D-5). Lotes construidos a mano en {@code merkle_batches}.
 */
@SpringBootTest(classes = OrganizationIntegrityQueryIntegrationTest.TestConfig.class, properties = {"spring.ai.openai.api-key=dummy"})
@Testcontainers
class OrganizationIntegrityQueryIntegrationTest {

    @Configuration
    @SpringBootApplication(scanBasePackages = {
            "com.traceability.core.infrastructure.persistence.mongo",
            "com.traceability.crypto.infrastructure.persistence.mongo"
    })
    @EnableMongoRepositories(basePackages = {
            "com.traceability.core.infrastructure.persistence.mongo",
            "com.traceability.crypto.infrastructure.persistence.mongo"
    })
    @Import({TraceabilityInfrastructureConfig.class, com.traceability.crypto.application.JcsHashAdapter.class,
            com.traceability.core.application.event.EventCanonicalMapper.class, OrganizationBoundaryPolicy.class,
            OrganizationIntegrityQuery.class})
    static class TestConfig {
    }

    @Container
    static MongoDBContainer mongoDBContainer = new MongoDBContainer(org.testcontainers.utility.DockerImageName.parse("mongo:6.0"))
            .withCommand("--replSet", "rs0");

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongoDBContainer::getReplicaSetUrl);
    }

    private static final String ORG = "org-A";
    private static final String OTHER = "org-B";

    @Autowired private MongoTemplate mongoTemplate;
    @Autowired private MerkleBatchRepositoryPort batches;
    @Autowired private OrganizationIntegrityQuery query;

    @BeforeEach
    void cleanUp() {
        mongoTemplate.dropCollection("event_store");
        mongoTemplate.dropCollection("merkle_batches");
        mongoTemplate.createCollection("event_store");
        mongoTemplate.createCollection("merkle_batches");
    }

    private static AuthorizationPrincipal admin(String org) {
        return new AuthorizationPrincipal("acct-" + org, org, Set.of(AuthorizationRole.ADMINISTRATOR), null);
    }

    /** Génesis de un fondo o de un activo de esa organización (lo que leen los directorios). */
    private void genesis(String streamId, String aggregateType, String eventType, String org, String batchId) {
        mongoTemplate.insert(TraceabilityEventDocument.builder().eventId(UUID.randomUUID().toString()).streamId(streamId)
                .aggregateType(aggregateType).sequence(1).eventType(eventType).recordedAt("2026-10-01T10:00:00Z")
                .payload(Map.of("organizationRef", org)).merkleBatchId(batchId).eventHash("h").build());
    }

    private void event(String streamId, long sequence, String batchId) {
        mongoTemplate.insert(TraceabilityEventDocument.builder().eventId(UUID.randomUUID().toString()).streamId(streamId)
                .aggregateType("PhysicalAsset").sequence(sequence).eventType("ASSET_DISPATCHED")
                .recordedAt("2026-10-02T10:00:00Z").merkleBatchId(batchId).eventHash("h").build());
    }

    private void batch(String batchId, AnchorStatus status, Instant anchoredAt) {
        batches.save(new MerkleBatch(batchId, Map.of("x", new SequenceRange(1, 1)), "root-" + batchId, java.util.List.of("h"),
                Instant.parse("2026-10-01T00:00:00Z"), status, "sepolia", "0xc", 1L, "0xtx-" + batchId,
                Instant.parse("2026-10-01T01:00:00Z"), anchoredAt, 9L, null));
    }

    // 10a: un lote que mezcla organizaciones solo da a A sus propios eventos
    @Test
    void t10a_aBatchMixingOrganizations_countsOnlyTheOwnEvents() {
        genesis("fund-A", "Fund", "FUNDS_CLEARED", ORG, "b-mixed");
        genesis("asset-A", "PhysicalAsset", "ASSET_REGISTERED", ORG, "b-mixed");
        event("asset-A", 2, "b-mixed");
        genesis("fund-B", "Fund", "FUNDS_CLEARED", OTHER, "b-mixed");
        event("fund-B", 2, "b-mixed");
        event("fund-B", 3, "b-mixed");
        batch("b-mixed", AnchorStatus.PENDING, null);

        OrganizationIntegrity mine = query.integrityOf(admin(ORG), ORG);
        OrganizationIntegrity theirs = query.integrityOf(admin(OTHER), OTHER);

        assertThat(mine.inPendingBatches()).isEqualTo(3);
        assertThat(theirs.inPendingBatches()).isEqualTo(3);
        assertThat(mine.unanchoredEvents()).isZero();
        assertThat(mine.underReviewEvents()).isZero();
        assertThat(mine.truncated()).isFalse();
    }

    // 10b: la clasificación de la tabla de G-3 (Bloque C), por evento
    @Test
    void t10b_underReviewInPendingAndUnanchored_followTheTableOfG3() {
        genesis("asset-A", "PhysicalAsset", "ASSET_REGISTERED", ORG, null);           // sin lote → unanchored
        event("asset-A", 2, "b-failed");                                            // FAILED → under review
        event("asset-A", 3, "b-collecting-failed");                                 // COLLECTING_FAILED → under review
        event("asset-A", 4, "b-mismatch");                                          // ANCHOR_MISMATCH → under review
        event("asset-A", 5, "b-mismatch");
        event("asset-A", 6, "b-stuck");                                             // STUCK → in pending
        event("asset-A", 7, "b-submitted");                                         // SUBMITTED → in pending
        event("asset-A", 8, "b-released");                                          // RELEASED → unanchored
        event("asset-A", 9, "b-not-saved");                                         // sin lote guardado → unanchored (H-C-2)
        event("asset-A", 10, "b-anchored");                                         // ANCHORED → ninguno de los tres
        batch("b-failed", AnchorStatus.FAILED, null);
        batch("b-collecting-failed", AnchorStatus.COLLECTING_FAILED, null);
        batch("b-mismatch", AnchorStatus.ANCHOR_MISMATCH, null);
        batch("b-stuck", AnchorStatus.STUCK, null);
        batch("b-submitted", AnchorStatus.SUBMITTED, null);
        batch("b-released", AnchorStatus.RELEASED, null);
        batch("b-anchored", AnchorStatus.ANCHORED, Instant.parse("2026-10-05T00:00:00Z"));

        OrganizationIntegrity r = query.integrityOf(admin(ORG), ORG);

        assertThat(r.underReviewEvents()).isEqualTo(4);
        assertThat(r.inPendingBatches()).isEqualTo(2);
        assertThat(r.unanchoredEvents()).isEqualTo(3);
        assertThat(r.lastAnchoredAt()).isEqualTo(Instant.parse("2026-10-05T00:00:00Z"));
    }

    // 10c: lastAnchoredAt es el mayor anchoredAt de los lotes ANCHORED con eventos propios
    @Test
    void t10c_lastAnchoredAt_isTheLatestOwnAnchoredBatch_neverAnotherOrganizations() {
        genesis("asset-A", "PhysicalAsset", "ASSET_REGISTERED", ORG, "b-old");
        event("asset-A", 2, "b-new");
        event("asset-A", 3, "b-mismatch-later");
        genesis("asset-B", "PhysicalAsset", "ASSET_REGISTERED", OTHER, "b-other");
        batch("b-old", AnchorStatus.ANCHORED, Instant.parse("2026-10-03T00:00:00Z"));
        batch("b-new", AnchorStatus.ANCHORED, Instant.parse("2026-10-04T00:00:00Z"));
        batch("b-mismatch-later", AnchorStatus.ANCHOR_MISMATCH, Instant.parse("2026-10-09T00:00:00Z"));
        batch("b-other", AnchorStatus.ANCHORED, Instant.parse("2026-10-08T00:00:00Z"));

        assertThat(query.integrityOf(admin(ORG), ORG).lastAnchoredAt()).isEqualTo(Instant.parse("2026-10-04T00:00:00Z"));
    }

    @Test
    void withoutAnchoredBatches_thereIsNoLastAnchoredAt() {
        genesis("asset-A", "PhysicalAsset", "ASSET_REGISTERED", ORG, null);

        OrganizationIntegrity r = query.integrityOf(admin(ORG), ORG);

        assertThat(r.lastAnchoredAt()).isNull();
        assertThat(r.unanchoredEvents()).isEqualTo(1);
        assertThat(r.checkedAt()).isNotNull();
    }
}
