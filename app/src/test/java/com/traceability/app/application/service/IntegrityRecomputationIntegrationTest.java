package com.traceability.app.application.service;

import com.traceability.contracts.HashPort;
import com.traceability.contracts.SequenceRange;
import com.traceability.core.application.event.EventCanonicalMapper;
import com.traceability.core.application.integrity.RecomputingEventIntegrityChecker;
import com.traceability.core.application.port.out.EventStorePort;
import com.traceability.core.application.port.out.UnanchoredEventRepositoryPort;
import com.traceability.core.domain.event.DomainEvent;
import com.traceability.core.domain.event.SystemActor;
import com.traceability.core.domain.fund.FundEventType;
import com.traceability.core.domain.fund.payloads.FundRegisteredV2Payload;
import com.traceability.core.domain.fund.payloads.FundsClearedV2Payload;
import com.traceability.core.domain.physicalasset.PhysicalAssetEventType;
import com.traceability.core.domain.physicalasset.payloads.AssetRegisteredV3Payload;
import com.traceability.app.config.TraceabilityInfrastructureConfig;
import com.traceability.crypto.application.port.in.IntegrityVerificationPort;
import com.traceability.crypto.application.port.out.MerkleBatchRepositoryPort;
import com.traceability.crypto.domain.AnchorStatus;
import com.traceability.crypto.domain.InconclusiveReason;
import com.traceability.crypto.domain.MerkleBatch;
import com.traceability.crypto.domain.MerkleTree;
import com.traceability.crypto.domain.StreamIdentity;
import com.traceability.crypto.domain.VerificationResult;
import com.traceability.crypto.domain.VerificationStatus;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Enmienda 1 de ADR-039 (aprobada por Carlos el 2026-10-08), definición de hecho 1 a 5, con Mongo real y el pipeline de
 * hash de producción. La verificación recalcula cada {@code eventHash} desde el payload, comprueba la cadena
 * {@code previousHash} desde el evento anterior a la cobertura y solo prueba la forma canónica anterior con eventos de
 * antes del corte de {@code 0579f41}.
 */
@SpringBootTest(classes = IntegrityRecomputationIntegrationTest.TestConfig.class, properties = {"spring.ai.openai.api-key=dummy"})
@Testcontainers
class IntegrityRecomputationIntegrationTest {

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
            EventCanonicalMapper.class, RecomputingEventIntegrityChecker.class})
    static class TestConfig {
        @Bean
        IntegrityVerificationPort integrityVerificationPort(MerkleBatchRepositoryPort batches,
                                                            UnanchoredEventRepositoryPort unanchored,
                                                            RecomputingEventIntegrityChecker checker) {
            return new IntegrityVerificationUseCase(batches, unanchored, checker);
        }
    }

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(org.testcontainers.utility.DockerImageName.parse("mongo:6.0"))
            .withCommand("--replSet", "rs0");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }

    static final Instant BEFORE_CUTOFF = Instant.parse("2026-09-10T12:00:00Z");
    static final Instant AFTER_CUTOFF = Instant.parse("2026-09-20T12:00:00Z");

    @Autowired MongoTemplate mongoTemplate;
    @Autowired EventStorePort eventStore;
    @Autowired MerkleBatchRepositoryPort batches;
    @Autowired IntegrityVerificationPort verification;
    @Autowired EventCanonicalMapper mapper;
    @Autowired HashPort hash;

    @BeforeEach
    void clean() {
        mongoTemplate.dropCollection("event_store");
        mongoTemplate.dropCollection("merkle_batches");
    }

    /** Fondo con tres eventos y un activo con cantidad decimal, escritos por el event store real. */
    void realStreams(String fund, String asset) {
        SystemActor actor = new SystemActor("test");
        eventStore.append(fund, "Fund", 0, List.of(
                new DomainEvent(FundEventType.FUND_REGISTERED, new FundRegisteredV2Payload("ORG-1", 500_000L, "COP", "CAMP-1", "dnr-1"), Instant.now()),
                new DomainEvent(FundEventType.FUNDS_CLEARED, new FundsClearedV2Payload("ORG-1", 300_000L, "pay-1", "COP", "CAMP-1", "dnr-1"), Instant.now()),
                new DomainEvent(FundEventType.FUNDS_CLEARED, new FundsClearedV2Payload("ORG-1", 200_000L, "pay-2", "COP", "CAMP-1", "dnr-1"), Instant.now())),
                actor);
        eventStore.append(asset, "PhysicalAsset", 0, List.of(new DomainEvent(PhysicalAssetEventType.ASSET_REGISTERED,
                new AssetRegisteredV3Payload(asset, "BLANKET", new BigDecimal("10.5000"), "UNITS", "bodega", "custodio",
                        null, asset, "alloc-1", null, "ORG-1", "dnr-1", null, "CAMP-1"), Instant.now())), actor);
    }

    /** Lote ANCHORED con las hojas guardadas en el momento de anclar. */
    String anchored(Map<String, SequenceRange> coverage) {
        List<String> leaves = new ArrayList<>();
        coverage.keySet().stream().sorted().forEach(s -> mongoTemplate.find(Query.query(Criteria.where("streamId").is(s)
                        .and("sequence").gte(coverage.get(s).fromSequence()).lte(coverage.get(s).toSequence()))
                .with(org.springframework.data.domain.Sort.by("sequence")), Document.class, "event_store")
                .forEach(d -> leaves.add(d.getString("eventHash"))));
        String id = UUID.randomUUID().toString();
        batches.save(new MerkleBatch(id, coverage, MerkleTree.build(leaves).getRoot(), leaves, Instant.now(),
                AnchorStatus.ANCHORED, "ganache-local", "0xc", 1L, "0xtx", Instant.now(), Instant.now(), 1L, null));
        return id;
    }

    Document event(String stream, long sequence) {
        return mongoTemplate.findOne(Query.query(Criteria.where("streamId").is(stream).and("sequence").is(sequence)),
                Document.class, "event_store");
    }

    /** Evento crudo como los de antes o después del corte, hasheado con la forma actual o la anterior. */
    @SuppressWarnings("unchecked")
    String rawEvent(String stream, long sequence, String previousHash, Instant recordedAt, boolean legacyForm, long amount) {
        String eventId = UUID.randomUUID().toString();
        FundRegisteredV2Payload payload = new FundRegisteredV2Payload("ORG-1", amount, "COP", "CAMP-1", "dnr-1");
        Map<String, Object> canonical = mapper.toCanonicalMap(eventId, stream, "Fund", sequence, "FUND_REGISTERED", "2.0",
                recordedAt, recordedAt, "TRACEABILITY_CORE", payload);
        Map<String, Object> toHash = new HashMap<>(canonical);
        if (legacyForm) {
            toHash.put("actorRef", "system:legacy");
        }
        String eventHash = hash.canonicalizeAndHash(toHash, previousHash);
        Document d = new Document("_id", eventId).append("streamId", stream).append("aggregateType", "Fund")
                .append("sequence", sequence).append("eventType", "FUND_REGISTERED").append("schemaVersion", "2.0")
                .append("occurredAt", recordedAt.toString()).append("recordedAt", recordedAt.toString())
                .append("actorRef", "system:legacy").append("origin", "TRACEABILITY_CORE")
                .append("payload", new Document((Map<String, Object>) canonical.get("payload")))
                .append("previousHash", previousHash).append("eventHash", eventHash);
        mongoTemplate.insert(d, "event_store");
        return eventHash;
    }

    @Test
    void realEventsWrittenByTheEventStore_matchWhenRecomputedFromThePayload() {
        realStreams("F-1", "A-1");
        String batch = anchored(Map.of("F-1", new SequenceRange(1, 3), "A-1", new SequenceRange(1, 1)));

        VerificationResult r = verification.verifyBatch(batch);

        assertThat(r.status()).as(r.toString()).isEqualTo(VerificationStatus.MATCH);
        assertThat(r.inconclusiveReason()).isNull();
    }

    // DoD 1
    @Test
    void aPayloadAlteredWithoutTouchingTheEventHash_isAMismatch_attributedToThatEvent() {
        realStreams("F-1", "A-1");
        String batch = anchored(Map.of("F-1", new SequenceRange(1, 3), "A-1", new SequenceRange(1, 1)));
        mongoTemplate.updateFirst(Query.query(Criteria.where("streamId").is("F-1").and("sequence").is(2L)),
                Update.update("payload.clearedAmount", 999_999L), "event_store");

        VerificationResult r = verification.verifyBatch(batch);

        assertThat(r.status()).isEqualTo(VerificationStatus.MISMATCH);
        assertThat(r.affectedSequences()).contains(new StreamIdentity("F-1", 2));
    }

    // DoD 2
    @Test
    void anEventReplacedWithItsHashesRecomputedInCascade_isAMismatch() {
        realStreams("F-1", "A-1");
        String batch = anchored(Map.of("F-1", new SequenceRange(1, 3)));
        // el atacante reescribe el evento 2 con otro importe y recalcula su eventHash y la cadena del 3
        Document e2 = event("F-1", 2);
        Document e3 = event("F-1", 3);
        String forged2 = recomputeWith(e2, Map.of("clearedAmount", 1L));
        String forged3 = recomputeWithPrevious(e3, forged2);
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(e2.get("_id"))),
                Update.update("payload.clearedAmount", 1L).set("eventHash", forged2), "event_store");
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(e3.get("_id"))),
                Update.update("previousHash", forged2).set("eventHash", forged3), "event_store");

        VerificationResult r = verification.verifyBatch(batch);

        assertThat(r.status()).isEqualTo(VerificationStatus.MISMATCH);
        assertThat(r.affectedSequences()).contains(new StreamIdentity("F-1", 2), new StreamIdentity("F-1", 3));
    }

    // DoD 2 (cadena desde el evento anterior a la cobertura)
    @Test
    void theChainIsCheckedFromTheEventBeforeTheCoverage() {
        realStreams("F-1", "A-1");
        String batch = anchored(Map.of("F-1", new SequenceRange(2, 3)));
        mongoTemplate.updateFirst(Query.query(Criteria.where("streamId").is("F-1").and("sequence").is(1L)),
                Update.update("eventHash", "f".repeat(64)), "event_store");

        VerificationResult r = verification.verifyBatch(batch);

        assertThat(r.status()).isEqualTo(VerificationStatus.MISMATCH);
        assertThat(r.affectedSequences()).contains(new StreamIdentity("F-1", 2));
    }

    // DoD 3
    @Test
    void eventsBeforeTheCutoff_hashedWithTheLegacyForm_match() {
        String h1 = rawEvent("F-OLD", 1, DomainEvent.GENESIS_HASH, BEFORE_CUTOFF, true, 100L);
        rawEvent("F-OLD", 2, h1, BEFORE_CUTOFF.plusSeconds(60), true, 200L);
        String batch = anchored(Map.of("F-OLD", new SequenceRange(1, 2)));

        VerificationResult r = verification.verifyBatch(batch);

        assertThat(r.status()).as(r.toString()).isEqualTo(VerificationStatus.MATCH);
    }

    // DoD 4
    @Test
    void anEventBeforeTheCutoffWhoseFormCannotBeDetermined_isInconclusive_neverAMismatch() {
        String h1 = rawEvent("F-OLD", 1, DomainEvent.GENESIS_HASH, BEFORE_CUTOFF, true, 100L);
        rawEvent("F-OLD", 2, h1, BEFORE_CUTOFF.plusSeconds(60), true, 200L);
        mongoTemplate.updateFirst(Query.query(Criteria.where("streamId").is("F-OLD").and("sequence").is(2L)),
                Update.update("payload.pledgedAmount", 201L), "event_store");
        String batch = anchored(Map.of("F-OLD", new SequenceRange(1, 2)));

        VerificationResult r = verification.verifyBatch(batch);

        assertThat(r.status()).isEqualTo(VerificationStatus.INCONCLUSIVE);
        assertThat(r.inconclusiveReason()).isEqualTo(InconclusiveReason.CANONICAL_FORM_UNKNOWN);
    }

    // DoD 5: la forma anterior solo vale antes del corte (Carlos, 2026-10-08)
    @Test
    void anEventAfterTheCutoffHashedWithTheLegacyForm_isAMismatch() {
        rawEvent("F-NEW", 1, DomainEvent.GENESIS_HASH, AFTER_CUTOFF, true, 100L);
        String batch = anchored(Map.of("F-NEW", new SequenceRange(1, 1)));

        VerificationResult r = verification.verifyBatch(batch);

        assertThat(r.status()).isEqualTo(VerificationStatus.MISMATCH);
        assertThat(r.affectedSequences()).containsExactly(new StreamIdentity("F-NEW", 1));
    }

    @Test
    void aBatchNotYetAnchored_isInconclusiveWithItsReason() {
        realStreams("F-1", "A-1");
        String id = anchored(Map.of("F-1", new SequenceRange(1, 3)));
        MerkleBatch b = batches.findByBatchId(id).orElseThrow();
        batches.save(new MerkleBatch(b.batchId(), b.coverage(), b.merkleRoot(), b.leafHashes(), b.createdAt(),
                AnchorStatus.SUBMITTED, b.network(), b.smartContractAddress(), b.nonceUsed(), b.transactionHash(),
                b.submittedAt(), null, null, null));

        VerificationResult r = verification.verifyBatch(id);

        assertThat(r.status()).isEqualTo(VerificationStatus.INCONCLUSIVE);
        assertThat(r.inconclusiveReason()).isEqualTo(InconclusiveReason.NOT_ANCHORED);
    }

    /** eventHash de un evento guardado con algunos campos del payload cambiados, con el pipeline de producción. */
    @SuppressWarnings("unchecked")
    String recomputeWith(Document e, Map<String, Object> payloadChanges) {
        Map<String, Object> payload = new HashMap<>((Map<String, Object>) e.get("payload"));
        payload.putAll(payloadChanges);
        return hashOf(e, payload, e.getString("previousHash"));
    }

    @SuppressWarnings("unchecked")
    String recomputeWithPrevious(Document e, String previousHash) {
        return hashOf(e, (Map<String, Object>) e.get("payload"), previousHash);
    }

    String hashOf(Document e, Map<String, Object> payload, String previousHash) {
        Map<String, Object> map = mapper.toCanonicalMap(String.valueOf(e.get("_id")), e.getString("streamId"),
                e.getString("aggregateType"), ((Number) e.get("sequence")).longValue(), e.getString("eventType"),
                e.getString("schemaVersion"), Instant.parse(e.getString("occurredAt")), Instant.parse(e.getString("recordedAt")),
                e.getString("origin"), mapper.convertPayload(payload, e.getString("eventType"), e.getString("schemaVersion")));
        return hash.canonicalizeAndHash(map, previousHash);
    }
}
