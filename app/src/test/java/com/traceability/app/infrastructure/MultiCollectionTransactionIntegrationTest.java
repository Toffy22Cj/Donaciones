package com.traceability.app.infrastructure;

import com.traceability.app.config.TraceabilityInfrastructureConfig;
import com.traceability.contracts.SequenceRange;
import com.traceability.core.application.port.out.UnanchoredEventRepositoryPort;
import com.traceability.core.infrastructure.persistence.mongo.MongoUnanchoredEventAdapter;
import com.traceability.core.infrastructure.persistence.mongo.TraceabilityEventDocument;
import com.traceability.crypto.application.port.out.MerkleBatchRepositoryPort;
import com.traceability.crypto.domain.AnchorStatus;
import com.traceability.crypto.domain.MerkleBatch;
import com.traceability.crypto.infrastructure.persistence.mongo.MerkleBatchDocument;
import com.traceability.crypto.infrastructure.persistence.mongo.MerkleBatchMongoAdapter;

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
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = MultiCollectionTransactionIntegrationTest.TestConfig.class, properties = {"spring.ai.openai.api-key=dummy"})
@Testcontainers
class MultiCollectionTransactionIntegrationTest {

    @Configuration
    @SpringBootApplication(scanBasePackages = {
            "com.traceability.core.infrastructure.persistence.mongo",
            "com.traceability.crypto.infrastructure.persistence.mongo"
    })
    @EnableMongoRepositories(basePackages = {
            "com.traceability.core.infrastructure.persistence.mongo",
            "com.traceability.crypto.infrastructure.persistence.mongo"
    })
    @Import({TraceabilityInfrastructureConfig.class, com.traceability.crypto.application.JcsHashAdapter.class, com.traceability.core.application.event.EventCanonicalMapper.class})
    static class TestConfig {
    }

    @Container
    static MongoDBContainer mongoDBContainer = new MongoDBContainer(org.testcontainers.utility.DockerImageName.parse("mongo:6.0"))
            .withCommand("--replSet", "rs0");

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongoDBContainer::getReplicaSetUrl);
    }

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private UnanchoredEventRepositoryPort eventAdapter;

    @Autowired
    private MerkleBatchRepositoryPort batchAdapter;

    @BeforeEach
    void cleanUp() {
        mongoTemplate.dropCollection("event_store");
        mongoTemplate.dropCollection("merkle_batches");
        
        mongoTemplate.createCollection("event_store");
        mongoTemplate.createCollection("merkle_batches");
    }

    @Test
    void shouldCommitToBothCollectionsAtomically_HappyPath() {
        // Given: We have an orphan event in core's collection
        TraceabilityEventDocument orphanEvent = TraceabilityEventDocument.builder()
                .eventId("evt-1")
                .streamId("stream-a")
                .sequence(1L)
                .eventHash("hash1")
                .build();
        mongoTemplate.save(orphanEvent);

        String batchId = UUID.randomUUID().toString();

        // When: We orchestrate the claim and the batch creation within a single TransactionTemplate
        transactionTemplate.executeWithoutResult(status -> {
            // Phase 1a: Claim in core
            Map<String, SequenceRange> coverage = eventAdapter.claimOrphansAndAssignBatch(batchId, 10, 100);
            assertThat(coverage).isNotEmpty();

            // Phase 1b: Save MerkleBatch(COLLECTING) in crypto
            MerkleBatch batch = new MerkleBatch(batchId, coverage, null, Instant.now(), AnchorStatus.COLLECTING, 
                "sepolia", "0x0", null, null, null, null, null, null);
            batchAdapter.save(batch);
        });

        // Then: The transaction committed successfully
        
        // 1. Verify event_store reflects the assigned merkleBatchId
        TraceabilityEventDocument updatedEvent = mongoTemplate.findById("evt-1", TraceabilityEventDocument.class);
        assertThat(updatedEvent).isNotNull();
        assertThat(updatedEvent.getMerkleBatchId()).isEqualTo(batchId);

        // 2. Verify merkle_batches contains the batch with the exact coverage map
        MerkleBatch savedBatch = batchAdapter.findByBatchId(batchId).orElseThrow();
        assertThat(savedBatch.status()).isEqualTo(AnchorStatus.COLLECTING);
        assertThat(savedBatch.coverage()).containsKey("stream-a");
        assertThat(savedBatch.coverage().get("stream-a").fromSequence()).isEqualTo(1L);
    }

    @Test
    void shouldRollbackBothCollectionsAtomically_WhenForcedFailure() {
        // Given: We have an orphan event
        TraceabilityEventDocument orphanEvent = TraceabilityEventDocument.builder()
                .eventId("evt-2")
                .streamId("stream-b")
                .sequence(1L)
                .eventHash("hash2")
                .build();
        mongoTemplate.save(orphanEvent);

        String batchId = UUID.randomUUID().toString();

        // When: We force an exception after the first mutation to verify atomicity across collections
        assertThatThrownBy(() -> {
            transactionTemplate.executeWithoutResult(status -> {
                // Phase 1a: Claim in core (this mutates event_store)
                Map<String, SequenceRange> coverage = eventAdapter.claimOrphansAndAssignBatch(batchId, 10, 100);
                assertThat(coverage).isNotEmpty();

                // Phase 1b: Save MerkleBatch in crypto (this mutates merkle_batches)
                MerkleBatch batch = new MerkleBatch(batchId, coverage, null, Instant.now(), AnchorStatus.COLLECTING, 
                    "sepolia", "0x0", null, null, null, null, null, null);
                batchAdapter.save(batch);

                // FORCE FAILURE BEFORE COMMIT
                throw new RuntimeException("Forced simulated failure");
            });
        }).hasMessageContaining("Forced simulated failure");

        // Then: NEITHER collection should retain the partial changes
        
        // 1. Verify event_store rolled back (the document remains an orphan)
        TraceabilityEventDocument notUpdatedEvent = mongoTemplate.findById("evt-2", TraceabilityEventDocument.class);
        assertThat(notUpdatedEvent).isNotNull();
        assertThat(notUpdatedEvent.getMerkleBatchId()).isNull();

        // 2. Verify merkle_batches rolled back (the batch document was never created)
        assertThat(batchAdapter.findByBatchId(batchId)).isEmpty();
    }
}
