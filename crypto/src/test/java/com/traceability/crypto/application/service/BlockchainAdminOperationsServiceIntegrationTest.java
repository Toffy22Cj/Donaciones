package com.traceability.crypto.application.service;

import com.traceability.crypto.domain.AnchorStatus;
import com.traceability.crypto.domain.MerkleBatch;
import com.traceability.crypto.domain.Resolution;
import com.traceability.crypto.infrastructure.persistence.mongo.MerkleBatchDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigInteger;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = BlockchainAdminOperationsServiceIntegrationTest.TestConfig.class)
@Testcontainers
class BlockchainAdminOperationsServiceIntegrationTest {

    @org.springframework.boot.autoconfigure.SpringBootApplication(scanBasePackages = "com.traceability.crypto.dummy")
    @org.springframework.data.mongodb.repository.config.EnableMongoRepositories(basePackages = "com.traceability.crypto.infrastructure.persistence.mongo")
    @org.springframework.context.annotation.Import({
        com.traceability.crypto.infrastructure.persistence.mongo.MerkleBatchMongoAdapter.class,
        com.traceability.crypto.application.service.BlockchainAdminOperationsService.class
    })
    static class TestConfig {
        @org.springframework.context.annotation.Bean
        public org.springframework.data.mongodb.MongoTransactionManager transactionManager(org.springframework.data.mongodb.MongoDatabaseFactory dbFactory) {
            return new org.springframework.data.mongodb.MongoTransactionManager(dbFactory);
        }
        
        @org.springframework.context.annotation.Bean
        public org.springframework.transaction.support.TransactionTemplate transactionTemplate(org.springframework.data.mongodb.MongoTransactionManager transactionManager) {
            return new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        }
    }

    @Container
    static MongoDBContainer mongoDBContainer = new MongoDBContainer("mongo:6.0");

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongoDBContainer::getReplicaSetUrl);
    }

    @Autowired
    private BlockchainAdminOperationsService adminOperationsService;

    @Autowired
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void setUp() {
        mongoTemplate.remove(new Query(), MerkleBatchDocument.class);
    }

    @AfterEach
    void tearDown() {
        mongoTemplate.remove(new Query(), MerkleBatchDocument.class);
    }

    @Test
    void shouldResolveStuckBatchByResubmitting() {
        // Arrange
        MerkleBatchDocument doc = new MerkleBatchDocument();
        doc.setBatchId("batch-123");
        doc.setStatus(AnchorStatus.STUCK);
        doc.setNonceUsed(42L);
        doc.setCreatedAt(Instant.now());
        mongoTemplate.save(doc);

        // Act
        adminOperationsService.resolveStuckBatch("batch-123", "RESUBMIT", "50000000000");

        // Assert
        MerkleBatchDocument updated = mongoTemplate.findById(doc.getId(), MerkleBatchDocument.class);
        assertThat(updated).isNotNull();
        assertThat(updated.getStatus()).isEqualTo(AnchorStatus.SUBMITTING);
        assertThat(updated.getResolution()).isEqualTo(Resolution.RESUBMIT);
        assertThat(updated.getMaxFeePerGasOverride()).isEqualTo(new BigInteger("50000000000"));
        assertThat(updated.getNonceUsed()).isEqualTo(42L); // Nonce is preserved
    }

    @Test
    void shouldResolveStuckBatchByAbandoning() {
        // Arrange
        MerkleBatchDocument doc = new MerkleBatchDocument();
        doc.setBatchId("batch-456");
        doc.setStatus(AnchorStatus.STUCK);
        doc.setNonceUsed(10L);
        mongoTemplate.save(doc);

        // Act
        adminOperationsService.resolveStuckBatch("batch-456", "ABANDON", null);

        // Assert
        MerkleBatchDocument updated = mongoTemplate.findById(doc.getId(), MerkleBatchDocument.class);
        assertThat(updated).isNotNull();
        assertThat(updated.getStatus()).isEqualTo(AnchorStatus.FAILED);
        assertThat(updated.getResolution()).isEqualTo(Resolution.ABANDON);
        assertThat(updated.getNonceUsed()).isEqualTo(10L);
    }

    @Test
    void shouldThrowExceptionIfBatchNotStuck() {
        // Arrange
        MerkleBatchDocument doc = new MerkleBatchDocument();
        doc.setBatchId("batch-789");
        doc.setStatus(AnchorStatus.PENDING);
        mongoTemplate.save(doc);

        // Act & Assert
        assertThatThrownBy(() -> adminOperationsService.resolveStuckBatch("batch-789", "RESUBMIT", "1000"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not found or not in STUCK state");
    }
}
