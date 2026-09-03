package com.traceability.crypto.infrastructure.persistence.mongo;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.traceability.crypto.domain.AnchorStatus;
import com.traceability.crypto.domain.MerkleBatch;
import com.traceability.crypto.domain.exception.NoPendingBatchAvailableException;

@SpringBootTest(classes = MerkleBatchMongoAdapterTest.TestConfig.class)
@Testcontainers
class MerkleBatchMongoAdapterTest {

    private static final Logger LOG = Logger.getLogger(MerkleBatchMongoAdapterTest.class.getName());

    @Configuration
    @SpringBootApplication(scanBasePackages = "com.traceability.crypto.infrastructure.persistence.mongo")
    @EnableMongoRepositories(basePackages = "com.traceability.crypto.infrastructure.persistence.mongo")
    static class TestConfig {
        @Bean
        MongoTransactionManager transactionManager(org.springframework.data.mongodb.MongoDatabaseFactory dbFactory) {
            return new MongoTransactionManager(dbFactory);
        }

        @Bean
        org.springframework.transaction.support.TransactionTemplate transactionTemplate(MongoTransactionManager transactionManager) {
            return new org.springframework.transaction.support.TransactionTemplate(transactionManager);
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
    private MerkleBatchMongoAdapter adapter;

    @Autowired
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void cleanUp() {
        mongoTemplate.dropCollection(MerkleBatchDocument.class);
        mongoTemplate.dropCollection(Web3NonceCounterDocument.class);
    }

    // ============================================================================
    // EXISTING TESTS (Concurrency and Rollback Validation)
    // ============================================================================

    @Test
    void testRollbackWhenNoPendingBatch() {
        // Arrange
        String network = "polygon-amoy";
        String contract = "0x123";
        adapter.seedNonceCounter(network, contract, 10L);

        // Act & Assert
        assertThrows(NoPendingBatchAvailableException.class, () -> {
            adapter.claimNextPendingBatchAndAssignNonceWithRetry(network, contract);
        });

        // Verify rollback: Counter should STILL be 10, not 11
        Web3NonceCounterDocument counter = mongoTemplate.findById(network + "-" + contract, Web3NonceCounterDocument.class);
        assertNotNull(counter, "Counter document should exist after failed claim");
        assertEquals(10L, counter.getNextNonce(), "Nonce counter should not advance if no batch was claimed");
    }

    @Test
    void testFindSubmittingWithoutTxHashOlderThan_ShouldNotReturnNewlyClaimedBatch() {
        // Arrange
        MerkleBatch batch = new MerkleBatch(
                "BATCH-NEWLY-CLAIMED", 101, 200, "root_new", Instant.now(), AnchorStatus.PENDING,
                null, null, null, null, null, null, null, null
        );
        adapter.save(batch);
        
        adapter.seedNonceCounter("polygon-amoy", "0x123", 5L);

        // Act - Claim the batch (sets status to SUBMITTING and submittedAt to Instant.now())
        Optional<MerkleBatch> claimed = adapter.claimNextPendingBatchAndAssignNonceWithRetry("polygon-amoy", "0x123");
        assertTrue(claimed.isPresent(), "Batch should be claimed");
        assertEquals(AnchorStatus.SUBMITTING, claimed.get().status());
        assertNotNull(claimed.get().submittedAt(), "submittedAt should be set upon claiming");

        // Assert - Try to find submitting older than 5 minutes ago (cutoff is 5 mins ago)
        Instant cutoff = Instant.now().minusSeconds(300);
        List<MerkleBatch> oldSubmittingBatches = adapter.findSubmittingWithoutTxHashOlderThan(cutoff);
        
        assertTrue(oldSubmittingBatches.isEmpty(), "Newly claimed batch should not be returned by findSubmittingWithoutTxHashOlderThan");
        
        // Assert - If cutoff is in the future, it should find it
        Instant futureCutoff = Instant.now().plusSeconds(60);
        List<MerkleBatch> allSubmittingBatches = adapter.findSubmittingWithoutTxHashOlderThan(futureCutoff);
        assertEquals(1, allSubmittingBatches.size(), "Batch should be found if cutoff is in the future");
        assertEquals("BATCH-NEWLY-CLAIMED", allSubmittingBatches.get(0).batchId());
    }

    @Test
    void testConcurrency_TwoThreadsClaimSingleBatch() throws InterruptedException, ExecutionException {
        // Arrange
        String network = "polygon-amoy";
        String contract = "0x123";
        adapter.seedNonceCounter(network, contract, 50L);

        MerkleBatch batch = new MerkleBatch(
                "BATCH-1", 1, 100, "root1", Instant.now(), AnchorStatus.PENDING, 
                null, null, null, null, null, null, null, null
        );
        adapter.save(batch);

        int numThreads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(1);
        List<Future<Optional<MerkleBatch>>> futures = new ArrayList<>();
        AtomicInteger exceptionCount = new AtomicInteger(0);

        // Act
        for (int i = 0; i < numThreads; i++) {
            futures.add(executor.submit(() -> {
                try {
                    latch.await();
                    return adapter.claimNextPendingBatchAndAssignNonceWithRetry(network, contract);
                } catch (NoPendingBatchAvailableException e) {
                    exceptionCount.incrementAndGet();
                    return Optional.empty();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    LOG.severe("Thread interrupted: " + ie.getMessage());
                    return Optional.empty();
                }
            }));
        }

        latch.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        // Assert
        int successCount = 0;
        MerkleBatch claimedBatch = null;

        for (Future<Optional<MerkleBatch>> future : futures) {
            Optional<MerkleBatch> result = future.get();
            if (result.isPresent()) {
                successCount++;
                claimedBatch = result.get();
            }
        }

        assertEquals(1, successCount, "Exactly one thread should successfully claim the batch");
        assertEquals(1, exceptionCount.get(), "Exactly one thread should get NoPendingBatchAvailableException");
        
        assertNotNull(claimedBatch);
        assertEquals(AnchorStatus.SUBMITTING, claimedBatch.status());
        assertEquals(50L, claimedBatch.nonceUsed());
        assertNotNull(claimedBatch.submittedAt(), "Batch should record the time it entered SUBMITTING");

        // Verify final counter state
        Web3NonceCounterDocument counter = mongoTemplate.findById(network + "-" + contract, Web3NonceCounterDocument.class);
        assertNotNull(counter);
        assertEquals(51L, counter.getNextNonce(), "Nonce counter should advance exactly once");
    }

    // ============================================================================
    // NEW TESTS FOR REPOSITORY EXTENSION METHODS
    // ============================================================================

    @Test
    void testFindSubmittingWithoutTxHashAndNonce_returnsEmptyWhenNone() {
        // No SUBMITTING batches exist => should return empty
        Optional<MerkleBatch> result = adapter.findSubmittingWithoutTxHashAndNonce();
        assertTrue(result.isEmpty(), "Should return empty when no SUBMITTING batch without txHash exists");
    }

    @Test
    void testFindSubmittingWithoutTxHashAndNonce_findsBatchWithoutTxHash() {
        // Arrange: Create a batch in SUBMITTING state without txHash
        String batchId = "BATCH-TX-SEARCH";
        Instant submissionTime = Instant.now();
        MerkleBatch batch = new MerkleBatch(
                batchId, 101, 200, "root2", Instant.now(), AnchorStatus.SUBMITTING,
                "polygon-amoy", "0xabc", 42L, null, submissionTime, null, null, null
        );
        adapter.save(batch);

        // Act
        Optional<MerkleBatch> found = adapter.findSubmittingWithoutTxHashAndNonce();

        // Assert
        assertTrue(found.isPresent(), "Should find SUBMITTING batch without txHash");
        assertEquals(batchId, found.get().batchId());
        assertEquals(42L, found.get().nonceUsed());
        assertNull(found.get().transactionHash(), "txHash should be null");
    }

    @Test
    void testFindSubmittingWithoutTxHashAndNonce_ignoresBatchesWithTxHash() {
        // Arrange: Create a batch in SUBMITTING state WITH txHash (should be ignored)
        MerkleBatch batch = new MerkleBatch(
                "BATCH-WITH-TX", 201, 300, "root3", Instant.now(), AnchorStatus.SUBMITTING,
                "polygon-amoy", "0xdef", 50L, "0xTXHASH123", Instant.now(), null, null, null
        );
        adapter.save(batch);

        // Act
        Optional<MerkleBatch> found = adapter.findSubmittingWithoutTxHashAndNonce();

        // Assert
        assertTrue(found.isEmpty(), "Should NOT find SUBMITTING batches that already have txHash");
    }

    @Test
    void testFindSubmittingWithoutTxHashOlderThan_ignoresBatchesTooNew() {
        // Arrange
        Instant cutoff = Instant.parse("2024-01-01T00:00:00Z");
        Instant recentTime = Instant.parse("2026-01-01T00:00:00Z"); // After cutoff
        
        MerkleBatch batch = new MerkleBatch(
                "RECENT-BATCH", 301, 400, "root4", Instant.now(), AnchorStatus.SUBMITTING,
                "polygon-amoy", "0x111", 60L, null, recentTime, null, null, null
        );
        adapter.save(batch);

        // Act
        List<MerkleBatch> stale = adapter.findSubmittingWithoutTxHashOlderThan(cutoff);

        // Assert
        assertTrue(stale.isEmpty(), "Should NOT find batches submitted after cutoff");
    }

    @Test
    void testFindSubmittingWithoutTxHashOlderThan_findsBatchesBefore() {
        // Arrange
        Instant cutoff = Instant.parse("2024-01-02T00:00:00Z");
        Instant oldTime = Instant.parse("2024-01-01T00:00:00Z"); // Before cutoff
        
        String batchId = "STALE-BATCH";
        MerkleBatch batch = new MerkleBatch(
                batchId, 401, 500, "root5", Instant.now(), AnchorStatus.SUBMITTING,
                "polygon-amoy", "0x222", 70L, null, oldTime, null, null, null
        );
        adapter.save(batch);

        // Act
        List<MerkleBatch> stale = adapter.findSubmittingWithoutTxHashOlderThan(cutoff);

        // Assert
        assertEquals(1, stale.size(), "Should find SUBMITTING batches older than cutoff");
        assertEquals(batchId, stale.get(0).batchId());
    }

    @Test
    void testKeepSubmittingWithSameNonce_preservesNonce() {
        // Arrange
        String batchId = "NONCE-KEEP-TEST";
        Long originalNonce = 88L;
        MerkleBatch batch = new MerkleBatch(
                batchId, 501, 600, "root6", Instant.now(), AnchorStatus.SUBMITTING,
                "polygon-amoy", "0x333", originalNonce, "0xOLDTXHASH", Instant.now(), null, null, null
        );
        adapter.save(batch);

        // Act: Call keepSubmittingWithSameNonce (should reset txHash but keep nonce and status)
        adapter.keepSubmittingWithSameNonce(batchId);

        // Assert
        MerkleBatch updated = adapter.findByBatchId(batchId).orElseThrow();
        assertEquals(AnchorStatus.SUBMITTING, updated.status(), "Status should still be SUBMITTING");
        assertEquals(originalNonce, updated.nonceUsed(), "Nonce should be preserved");
        assertNull(updated.transactionHash(), "txHash should be cleared");
    }

    @Test
    void testReconcileSubmittingTimeout_setsNonceAndResets() {
        // Arrange
        String batchId = "TIMEOUT-RECONCILE-TEST";
        MerkleBatch batch = new MerkleBatch(
                batchId, 601, 700, "root7", Instant.now(), AnchorStatus.SUBMITTED,
                "polygon-amoy", "0x444", null, "0xTXHASH", Instant.now(), null, null, null
        );
        adapter.save(batch);

        // Act: Reconcile after timeout, assigning a new nonce
        Long newNonce = 99L;
        adapter.reconcileSubmittingTimeout(batchId, newNonce);

        // Assert
        MerkleBatch updated = adapter.findByBatchId(batchId).orElseThrow();
        assertEquals(AnchorStatus.SUBMITTING, updated.status(), "Status should be SUBMITTING after reconcile");
        assertEquals(newNonce, updated.nonceUsed(), "Nonce should be set to the reconciled value");
    }

    @Test
    void testMarkStuck_changesStatus() {
        // Arrange
        String batchId = "STUCK-TEST";
        MerkleBatch batch = new MerkleBatch(
                batchId, 701, 800, "root8", Instant.now(), AnchorStatus.SUBMITTING,
                "polygon-amoy", "0x555", 11L, null, Instant.now(), null, null, null
        );
        adapter.save(batch);

        // Act
        adapter.markStuck(batchId);

        // Assert
        MerkleBatch marked = adapter.findByBatchId(batchId).orElseThrow();
        assertEquals(AnchorStatus.STUCK, marked.status(), "Status should be changed to STUCK");
    }

    @Test
    void testMarkFailed_changesStatus() {
        // Arrange
        String batchId = "FAILED-TEST";
        MerkleBatch batch = new MerkleBatch(
                batchId, 801, 900, "root9", Instant.now(), AnchorStatus.SUBMITTING,
                "polygon-amoy", "0x666", 12L, null, Instant.now(), null, null, null
        );
        adapter.save(batch);

        // Act
        adapter.markFailed(batchId);

        // Assert
        MerkleBatch marked = adapter.findByBatchId(batchId).orElseThrow();
        assertEquals(AnchorStatus.FAILED, marked.status(), "Status should be changed to FAILED");
    }

    @Test
    void testMarkAnchored_setsBlockNumberAndTimestamp() {
        // Arrange
        String batchId = "ANCHORED-TEST";
        Long blockNumber = 19123456L;
        Instant anchorTime = Instant.parse("2026-01-15T12:00:00Z");
        
        MerkleBatch batch = new MerkleBatch(
                batchId, 901, 1000, "root10", Instant.now(), AnchorStatus.SUBMITTED,
                "polygon-amoy", "0x777", 13L, "0xTXHASH", Instant.now(), null, null, null
        );
        adapter.save(batch);

        // Act
        adapter.markAnchored(batchId, blockNumber, anchorTime);

        // Assert
        MerkleBatch marked = adapter.findByBatchId(batchId).orElseThrow();
        assertEquals(AnchorStatus.ANCHORED, marked.status(), "Status should be ANCHORED");
        assertEquals(blockNumber, marked.confirmedBlockNumber(), "Block number should be set");
        assertEquals(anchorTime, marked.anchoredAt(), "Anchor time should be set");
    }

    @Test
    void testMarkAnchorMismatch_changesStatus() {
        // Arrange
        String batchId = "MISMATCH-TEST";
        MerkleBatch batch = new MerkleBatch(
                batchId, 1001, 1100, "root11", Instant.now(), AnchorStatus.SUBMITTED,
                "polygon-amoy", "0x888", 14L, "0xTXHASH", Instant.now(), null, null, null
        );
        adapter.save(batch);

        // Act
        adapter.markAnchorMismatch(batchId);

        // Assert
        MerkleBatch marked = adapter.findByBatchId(batchId).orElseThrow();
        assertEquals(AnchorStatus.ANCHOR_MISMATCH, marked.status(), "Status should be ANCHOR_MISMATCH");
    }

    @Test
    void testFindSubmittedOlderFirst_returnsSorted() {
        // Arrange
        Instant time1 = Instant.parse("2024-01-01T00:00:00Z");
        Instant time2 = Instant.parse("2024-01-02T00:00:00Z");
        
        MerkleBatch batch1 = new MerkleBatch(
                "SUBMITTED-1", 1101, 1200, "root12", Instant.now(), AnchorStatus.SUBMITTED,
                "polygon-amoy", "0x999", 15L, "0xTX1", time2, null, null, null
        );
        MerkleBatch batch2 = new MerkleBatch(
                "SUBMITTED-2", 1201, 1300, "root13", Instant.now(), AnchorStatus.SUBMITTED,
                "polygon-amoy", "0xaaa", 16L, "0xTX2", time1, null, null, null
        );
        
        adapter.save(batch1);
        adapter.save(batch2);

        // Act
        List<MerkleBatch> submitted = adapter.findSubmittedOlderFirst();

        // Assert
        assertEquals(2, submitted.size(), "Should find both SUBMITTED batches");
        assertEquals("SUBMITTED-2", submitted.get(0).batchId(), "Oldest should come first");
        assertEquals("SUBMITTED-1", submitted.get(1).batchId(), "Newer should come second");
    }
}
