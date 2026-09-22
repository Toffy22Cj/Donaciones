package com.traceability.app.application.service;

import com.traceability.app.config.TraceabilityInfrastructureConfig;
import com.traceability.contracts.SequenceRange;
import com.traceability.core.application.port.out.UnanchoredEventRepositoryPort;
import com.traceability.core.infrastructure.persistence.mongo.TraceabilityEventDocument;
import com.traceability.crypto.application.port.in.IntegrityVerificationPort;
import com.traceability.crypto.application.port.out.MerkleBatchRepositoryPort;
import com.traceability.crypto.domain.AnchorStatus;
import com.traceability.crypto.domain.MerkleBatch;
import com.traceability.crypto.domain.MerkleTree;
import com.traceability.crypto.domain.StreamIdentity;
import com.traceability.crypto.domain.VerificationResult;
import com.traceability.crypto.domain.VerificationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = IntegrityVerificationUseCaseIntegrationTest.TestConfig.class, properties = {"spring.ai.openai.api-key=dummy"})
@Testcontainers
class IntegrityVerificationUseCaseIntegrationTest {

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
        @Bean
        public IntegrityVerificationPort integrityVerificationPort(
                MerkleBatchRepositoryPort merkleBatchRepositoryPort,
                UnanchoredEventRepositoryPort unanchoredEventRepositoryPort) {
            return new IntegrityVerificationUseCase(merkleBatchRepositoryPort, unanchoredEventRepositoryPort);
        }
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
    private MerkleBatchRepositoryPort batchAdapter;

    @Autowired
    private IntegrityVerificationPort integrityVerificationPort;

    @BeforeEach
    void cleanUp() {
        mongoTemplate.dropCollection("event_store");
        mongoTemplate.dropCollection("merkle_batches");

        mongoTemplate.createCollection("event_store");
        mongoTemplate.createCollection("merkle_batches");
    }

    @Test
    void shouldVerifyMatchAndDetectMismatchWhenEventIsAlteredInDatabase() {
        // 1. Create real events in event_store
        String batchId = UUID.randomUUID().toString();
        
        TraceabilityEventDocument event1 = TraceabilityEventDocument.builder()
                .eventId("evt-1")
                .streamId("stream-a")
                .sequence(1L)
                .eventHash("hash1")
                .merkleBatchId(batchId)
                .build();
        mongoTemplate.save(event1);

        TraceabilityEventDocument event2 = TraceabilityEventDocument.builder()
                .eventId("evt-2")
                .streamId("stream-b")
                .sequence(1L)
                .eventHash("hash2")
                .merkleBatchId(batchId)
                .build();
        mongoTemplate.save(event2);

        // 2. Create the MerkleBatch with original leafHashes in ANCHORED status
        Map<String, SequenceRange> coverage = Map.of(
                "stream-a", new SequenceRange(1, 1),
                "stream-b", new SequenceRange(1, 1)
        );
        List<String> originalLeaves = List.of("hash1", "hash2");
        String originalRoot = MerkleTree.build(originalLeaves).getRoot();

        MerkleBatch batch = new MerkleBatch(
                batchId,
                coverage,
                originalRoot,
                originalLeaves,
                Instant.now(),
                AnchorStatus.ANCHORED,
                "sepolia",
                "0x123",
                1L,
                "0xabc",
                Instant.now(),
                Instant.now(),
                100L,
                null
        );
        batchAdapter.save(batch);

        // 3. Verify MATCH before any alteration
        VerificationResult matchResult = integrityVerificationPort.verifyBatch(batchId);
        assertThat(matchResult.status()).isEqualTo(VerificationStatus.MATCH);
        assertThat(matchResult.diagnosisComplete()).isTrue();
        assertThat(matchResult.affectedSequences()).isEmpty();

        // 4. Alter an event in the database directly to simulate tampering
        TraceabilityEventDocument tamperedEvent = mongoTemplate.findById("evt-2", TraceabilityEventDocument.class);
        assertThat(tamperedEvent).isNotNull();
        tamperedEvent.setEventHash("hash2-tampered");
        mongoTemplate.save(tamperedEvent);

        // 5. Verify MISMATCH after alteration
        VerificationResult mismatchResult = integrityVerificationPort.verifyBatch(batchId);
        
        assertThat(mismatchResult.status()).isEqualTo(VerificationStatus.MISMATCH);
        assertThat(mismatchResult.diagnosisComplete()).isTrue();
        
        // Affected sequences should pinpoint exactly stream-b at sequence 1
        assertThat(mismatchResult.affectedSequences()).hasSize(1);
        StreamIdentity tamperedIdentity = mismatchResult.affectedSequences().get(0);
        assertThat(tamperedIdentity.streamId()).isEqualTo("stream-b");
        assertThat(tamperedIdentity.sequence()).isEqualTo(1L);
    }
}
