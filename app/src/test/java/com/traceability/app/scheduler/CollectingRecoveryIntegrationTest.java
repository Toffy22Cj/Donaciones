package com.traceability.app.scheduler;

import com.traceability.app.TraceabilityApplication;
import com.traceability.app.application.anchoring.CollectingFailedBatchService;
import com.traceability.contracts.SequenceRange;
import com.traceability.core.application.port.out.EventStorePort;
import com.traceability.core.application.port.out.UnanchoredEventRepositoryPort;
import com.traceability.core.domain.event.DomainEvent;
import com.traceability.core.domain.event.SystemActor;
import com.traceability.core.domain.fund.FundEventType;
import com.traceability.core.domain.fund.payloads.FundsClearedV2Payload;
import com.traceability.crypto.application.port.out.MerkleBatchRepositoryPort;
import com.traceability.crypto.domain.AnchorStatus;
import com.traceability.crypto.domain.MerkleBatch;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Enmienda 1 de ADR-039 §2.3 (aprobada por Carlos el 2026-10-08), definición de hecho 6 a 10, con Mongo real: tope de
 * recuperación con COLLECTING_FAILED, RETRY y RELEASE → RELEASED (auditado y filtrado por batch), la reclamación de
 * eventos nuevos aunque un batch sea ilegible y la contigüidad de batches sucesivos.
 */
@SpringBootTest(classes = TraceabilityApplication.class)
@Testcontainers
@TestPropertySource(properties = {
    "crypto.anchor.poll.delay=9999999",
    "crypto.anchor.submit.delay=9999999",
    "crypto.anchor.stuck-monitor.delay=9999999",
    "traceability.anchor.producer.interval-ms=9999999",
    "crypto.anchor.collecting-recovery.max-attempts=2",
    "crypto.anchor.collecting-recovery.max-per-cycle=1",
    "crypto.web3j.private-key=0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
    "crypto.web3j.node-url=http://dummy-node",
    "spring.ai.openai.api-key=dummy-api-key"
})
class CollectingRecoveryIntegrationTest {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:6.0")).withCommand("--replSet", "rs0");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }

    @Autowired BlockchainAnchorProducer producer;
    @Autowired MerkleBatchRepositoryPort batches;
    @Autowired UnanchoredEventRepositoryPort unanchored;
    @Autowired EventStorePort eventStore;
    @Autowired CollectingFailedBatchService resolutions;
    @Autowired MongoTemplate mongoTemplate;

    static final Instant LONG_AGO = Instant.now().minus(Duration.ofHours(2));

    @BeforeEach
    void clean() {
        mongoTemplate.remove(new Query(), "event_store");
        mongoTemplate.remove(new Query(), "merkle_batches");
        mongoTemplate.remove(new Query(), "batch_release_audit");
    }

    void append(String stream, long expectedVersion, int events) {
        List<DomainEvent> list = new ArrayList<>();
        for (int i = 0; i < events; i++) {
            list.add(new DomainEvent(FundEventType.FUNDS_CLEARED,
                    new FundsClearedV2Payload("ORG-1", 100L + i, "pay-" + i, "COP", "CAMP-1", "dnr-1"), Instant.now()));
        }
        eventStore.append(stream, "Fund", expectedVersion, list, new SystemActor("test"));
    }

    void collecting(String batchId, Map<String, SequenceRange> coverage, AnchorStatus status, int attempts) {
        batches.save(new MerkleBatch(batchId, coverage, null, null, LONG_AGO, status, null, null, null, null, null, null,
                null, null, null, attempts));
    }

    AnchorStatus status(String batchId) {
        return batches.findByBatchId(batchId).orElseThrow().status();
    }

    void claimInto(String batchId, String stream, long from, long to) {
        mongoTemplate.updateMulti(Query.query(Criteria.where("streamId").is(stream).and("sequence").gte(from).lte(to)),
                Update.update("merkleBatchId", batchId), "event_store");
    }

    String merkleBatchIdOf(String stream, long sequence) {
        return mongoTemplate.findOne(Query.query(Criteria.where("streamId").is(stream).and("sequence").is(sequence)),
                Document.class, "event_store").getString("merkleBatchId");
    }

    // DoD 6
    @Test
    void aBatchThatExhaustsTheCap_becomesCollectingFailed_andStopsBlockingTheQueue() {
        // el roto es más antiguo y la recuperación solo atiende uno por ciclo (max-per-cycle = 1)
        collecting("B-BROKEN", Map.of("S-GHOST", new SequenceRange(1, 1)), AnchorStatus.COLLECTING, 0);
        append("S-OK", 0, 2);
        claimInto("B-OK", "S-OK", 1, 2);
        batches.save(new MerkleBatch("B-OK", Map.of("S-OK", new SequenceRange(1, 2)), null, null,
                LONG_AGO.plusSeconds(60), AnchorStatus.COLLECTING, null, null, null, null, null, null, null, null, null, 0));

        for (int cycle = 0; cycle < 4; cycle++) {
            producer.produceBatch();
        }

        assertThat(status("B-BROKEN")).isEqualTo(AnchorStatus.COLLECTING_FAILED);
        assertThat(status("B-OK")).as("ya no lo bloquea el roto").isEqualTo(AnchorStatus.PENDING);
    }

    // DoD 7
    @Test
    void release_freesOnlyThisBatchsEventsInItsCoverage_andWritesTheAudit() {
        append("S-R", 0, 3);
        claimInto("B-REL", "S-R", 1, 2);
        claimInto("B-OTHER", "S-R", 3, 3);
        collecting("B-REL", Map.of("S-R", new SequenceRange(1, 3)), AnchorStatus.COLLECTING_FAILED, 3);

        resolutions.release("B-REL", "operador-demo", "lote irrecuperable");

        assertThat(status("B-REL")).isEqualTo(AnchorStatus.RELEASED);
        assertThat(batches.findByBatchId("B-REL").orElseThrow().coverage()).containsKey("S-R");
        assertThat(merkleBatchIdOf("S-R", 1)).isNull();
        assertThat(merkleBatchIdOf("S-R", 2)).isNull();
        assertThat(merkleBatchIdOf("S-R", 3)).as("de otro batch: no se toca").isEqualTo("B-OTHER");
        Document audit = mongoTemplate.findOne(Query.query(Criteria.where("batchId").is("B-REL")), Document.class,
                "batch_release_audit");
        assertThat(audit).isNotNull();
        assertThat(audit.getString("operator")).isEqualTo("operador-demo");
        assertThat(audit.getString("reason")).isEqualTo("lote irrecuperable");
        assertThat(audit.getList("eventIds", String.class)).hasSize(2);
        assertThat(audit.get("coverage")).isNotNull();

        // los eventos liberados vuelven a ser reclamables
        producer.produceBatch();
        assertThat(merkleBatchIdOf("S-R", 1)).isNotNull().isNotEqualTo("B-REL");
    }

    @Test
    void releaseAndRetry_onlyActOnCollectingFailed_andChangeNothingOtherwise() {
        append("S-X", 0, 1);
        claimInto("B-LIVE", "S-X", 1, 1);
        collecting("B-LIVE", Map.of("S-X", new SequenceRange(1, 1)), AnchorStatus.COLLECTING, 0);

        assertThatThrownBy(() -> resolutions.release("B-LIVE", "op", "x")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> resolutions.retry("B-LIVE")).isInstanceOf(IllegalStateException.class);
        assertThat(status("B-LIVE")).isEqualTo(AnchorStatus.COLLECTING);
        assertThat(merkleBatchIdOf("S-X", 1)).isEqualTo("B-LIVE");
        assertThat(mongoTemplate.count(new Query(), "batch_release_audit")).isZero();
    }

    // DoD 8
    @Test
    void retry_putsItBackToCollectingWithZeroAttempts_andItIsRecovered() {
        append("S-T", 0, 2);
        claimInto("B-RETRY", "S-T", 1, 2);
        collecting("B-RETRY", Map.of("S-T", new SequenceRange(1, 2)), AnchorStatus.COLLECTING_FAILED, 3);

        resolutions.retry("B-RETRY");
        MerkleBatch b = batches.findByBatchId("B-RETRY").orElseThrow();
        assertThat(b.status()).isEqualTo(AnchorStatus.COLLECTING);
        assertThat(b.recoveryAttempts()).isZero();

        producer.produceBatch();
        assertThat(status("B-RETRY")).isEqualTo(AnchorStatus.PENDING);
    }

    // DoD 9
    @Test
    void anUnreadableCollectingBatch_doesNotStopClaimingNewEventsInTheSameCycle() {
        mongoTemplate.insert(new Document("_id", UUID.randomUUID().toString()).append("batchId", "B-LEGACY")
                .append("status", "COLLECTING").append("createdAt", java.util.Date.from(LONG_AGO))
                .append("recoveryAttempts", 0), "merkle_batches");
        append("S-N", 0, 2);

        producer.produceBatch();

        String claimed = merkleBatchIdOf("S-N", 1);
        assertThat(claimed).isNotNull();
        assertThat(status(claimed)).isEqualTo(AnchorStatus.PENDING);
    }

    // DoD 10
    @Test
    void anEventAppendedBetweenTwoClaims_leavesSuccessiveBatchesContiguous() {
        append("S-C", 0, 2);
        producer.produceBatch();
        String first = merkleBatchIdOf("S-C", 1);
        append("S-C", 2, 1);
        producer.produceBatch();
        String second = merkleBatchIdOf("S-C", 3);

        assertThat(batches.findByBatchId(first).orElseThrow().coverage()).containsEntry("S-C", new SequenceRange(1, 2));
        assertThat(second).isNotEqualTo(first);
        assertThat(batches.findByBatchId(second).orElseThrow().coverage()).containsEntry("S-C", new SequenceRange(3, 3));
    }
}
