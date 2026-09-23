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
import static org.junit.jupiter.api.Assertions.assertFalse;
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

        // Act: claimNextPendingBatchAndAssignNonceWithRetry catches NoPendingBatchAvailableException internally
        // and returns Optional.empty()
        Optional<MerkleBatch> result = adapter.claimNextPendingBatchAndAssignNonceWithRetry(network, contract);
        assertTrue(result.isEmpty(), "Should return empty when no PENDING batch exists");

        // Verify rollback: Counter should STILL be 10, not 11
        Web3NonceCounterDocument counter = mongoTemplate.findById(network + "-" + contract, Web3NonceCounterDocument.class);
        assertNotNull(counter, "Counter document should exist after failed claim");
        assertEquals(10L, counter.getNextNonce(), "Nonce counter should not advance if no batch was claimed");
    }

    @Test
    void testFindSubmittingWithoutTxHashOlderThan_ShouldNotReturnNewlyClaimedBatch() {
        // Arrange
        MerkleBatch batch = new MerkleBatch(
                "BATCH-NEWLY-CLAIMED", java.util.Map.of("dummy", new com.traceability.contracts.SequenceRange(1, 10)), "root_new", Instant.now(), AnchorStatus.PENDING,
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
                "BATCH-1", java.util.Map.of("dummy", new com.traceability.contracts.SequenceRange(1, 10)), "root1", Instant.now(), AnchorStatus.PENDING, 
                null, null, null, null, null, null, null, null
        );
        adapter.save(batch);

        int numThreads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(1);
        List<Future<Optional<MerkleBatch>>> futures = new ArrayList<>();

        // Act: claimNextPendingBatchAndAssignNonceWithRetry returns Optional.empty() when no batch
        // is available (catches NoPendingBatchAvailableException internally)
        for (int i = 0; i < numThreads; i++) {
            futures.add(executor.submit(() -> {
                try {
                    latch.await();
                    return adapter.claimNextPendingBatchAndAssignNonceWithRetry(network, contract);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    LOG.severe("Thread interrupted: " + ie.getMessage());
                    return Optional.<MerkleBatch>empty();
                }
            }));
        }

        latch.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        // Assert
        int successCount = 0;
        int emptyCount = 0;
        MerkleBatch claimedBatch = null;

        for (Future<Optional<MerkleBatch>> future : futures) {
            Optional<MerkleBatch> result = future.get();
            if (result.isPresent()) {
                successCount++;
                claimedBatch = result.get();
            } else {
                emptyCount++;
            }
        }

        assertEquals(1, successCount, "Exactly one thread should successfully claim the batch");
        assertEquals(1, emptyCount, "Exactly one thread should get Optional.empty()");
        
        assertNotNull(claimedBatch);
        assertEquals(AnchorStatus.SUBMITTING, claimedBatch.status());
        assertEquals(50L, claimedBatch.nonceUsed());
        assertNotNull(claimedBatch.submittedAt(), "Batch should record the time it entered SUBMITTING");

        // Verify final counter state
        Web3NonceCounterDocument counter = mongoTemplate.findById(network + "-" + contract, Web3NonceCounterDocument.class);
        assertNotNull(counter);
        assertEquals(51L, counter.getNextNonce(), "Nonce counter should advance exactly once");
    }

    @Test
    void testTransitionCollectingToPending_DoubleCallYieldsBenignNoOp() {
        // Arrange
        String batchId = "DOUBLE-TRANSITION-TEST";
        MerkleBatch batch = new MerkleBatch(
                batchId, java.util.Map.of("dummy", new com.traceability.contracts.SequenceRange(1, 10)), null, Instant.now(), AnchorStatus.COLLECTING,
                null, null, null, null, null, null, null, null
        );
        adapter.save(batch);

        String merkleRoot = "0xroot123";
        List<String> leafHashes = List.of("hash1", "hash2");

        // Act 1: First attempt
        boolean firstTransition = adapter.transitionCollectingToPending(batchId, merkleRoot, leafHashes);
        
        // Assert 1: First attempt should succeed and modify the document
        assertTrue(firstTransition, "First transition should succeed because status is COLLECTING");
        MerkleBatch afterFirst = adapter.findByBatchId(batchId).orElseThrow();
        assertEquals(AnchorStatus.PENDING, afterFirst.status());
        assertEquals(merkleRoot, afterFirst.merkleRoot());
        assertEquals(leafHashes, afterFirst.leafHashes());

        // Act 2: Second attempt with the exact same values
        boolean secondTransition = adapter.transitionCollectingToPending(batchId, merkleRoot, leafHashes);

        // Assert 2: Second attempt should return false, leaving the document intact
        assertFalse(secondTransition, "Second transition should return false because status is no longer COLLECTING");
        MerkleBatch afterSecond = adapter.findByBatchId(batchId).orElseThrow();
        assertEquals(AnchorStatus.PENDING, afterSecond.status(), "Status should remain PENDING");
        assertEquals(merkleRoot, afterSecond.merkleRoot(), "merkleRoot should remain intact");
        assertEquals(leafHashes, afterSecond.leafHashes(), "leafHashes should remain intact");
    }

    // ============================================================================
    // NEW TESTS FOR REPOSITORY EXTENSION METHODS
    // ============================================================================

    @Test
    void testStreamByStatus_evaluatesLazilyFromMongo() {
        // Arrange
        // We create 3 documents directly in Mongo to bypass the domain constraints on saving
        // Batch 1: Valid
        MerkleBatchDocument doc1 = new MerkleBatchDocument();
        doc1.setBatchId("LAZY-BATCH-1");
        doc1.setStatus(AnchorStatus.ANCHORED);
        doc1.setCoverage(java.util.Map.of("dummy", new com.traceability.contracts.SequenceRange(1, 10)));
        mongoTemplate.save(doc1);

        // Batch 2: Legacy (missing coverage) -> adapter.toDomain() throws LegacyBatchCoverageUnavailableException
        MerkleBatchDocument doc2 = new MerkleBatchDocument();
        doc2.setBatchId("LAZY-BATCH-2");
        doc2.setStatus(AnchorStatus.ANCHORED);
        doc2.setCoverage(null); // LEGACY BATCH
        mongoTemplate.save(doc2);

        // Batch 3: Valid
        MerkleBatchDocument doc3 = new MerkleBatchDocument();
        doc3.setBatchId("LAZY-BATCH-3");
        doc3.setStatus(AnchorStatus.ANCHORED);
        doc3.setCoverage(java.util.Map.of("dummy", new com.traceability.contracts.SequenceRange(11, 20)));
        mongoTemplate.save(doc3);

        // Act
        // If streamByStatus was evaluating eagerly (e.g. converting everything to a List first), 
        // this call would throw LegacyBatchCoverageUnavailableException immediately on LAZY-BATCH-2.
        // Because it's truly lazy, it returns the stream successfully.
        try (java.util.stream.Stream<MerkleBatch> stream = adapter.streamByStatus(AnchorStatus.ANCHORED)) {
            assertNotNull(stream, "Stream should be returned successfully without evaluating elements");

            // Assert
            // We can safely consume the first element without triggering evaluation of the second
            Optional<MerkleBatch> first = stream.findFirst();
            assertTrue(first.isPresent());
            assertEquals("LAZY-BATCH-1", first.get().batchId());
        }
    }

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
                batchId, java.util.Map.of("dummy", new com.traceability.contracts.SequenceRange(1, 10)), "root2", Instant.now(), AnchorStatus.SUBMITTING,
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
                "BATCH-WITH-TX", java.util.Map.of("dummy", new com.traceability.contracts.SequenceRange(1, 10)), "root3", Instant.now(), AnchorStatus.SUBMITTING,
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
                "RECENT-BATCH", java.util.Map.of("dummy", new com.traceability.contracts.SequenceRange(1, 10)), "root4", Instant.now(), AnchorStatus.SUBMITTING,
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
                batchId, java.util.Map.of("dummy", new com.traceability.contracts.SequenceRange(1, 10)), "root5", Instant.now(), AnchorStatus.SUBMITTING,
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
                batchId, java.util.Map.of("dummy", new com.traceability.contracts.SequenceRange(1, 10)), "root6", Instant.now(), AnchorStatus.SUBMITTING,
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
                batchId, java.util.Map.of("dummy", new com.traceability.contracts.SequenceRange(1, 10)), "root7", Instant.now(), AnchorStatus.SUBMITTED,
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
                batchId, java.util.Map.of("dummy", new com.traceability.contracts.SequenceRange(1, 10)), "root8", Instant.now(), AnchorStatus.SUBMITTING,
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
                batchId, java.util.Map.of("dummy", new com.traceability.contracts.SequenceRange(1, 10)), "root9", Instant.now(), AnchorStatus.SUBMITTING,
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
                batchId, java.util.Map.of("dummy", new com.traceability.contracts.SequenceRange(1, 10)), "root10", Instant.now(), AnchorStatus.SUBMITTED,
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
                batchId, java.util.Map.of("dummy", new com.traceability.contracts.SequenceRange(1, 10)), "root11", Instant.now(), AnchorStatus.SUBMITTED,
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
                "SUBMITTED-1", java.util.Map.of("dummy", new com.traceability.contracts.SequenceRange(1, 10)), "root12", Instant.now(), AnchorStatus.SUBMITTED,
                "polygon-amoy", "0x999", 15L, "0xTX1", time2, null, null, null
        );
        MerkleBatch batch2 = new MerkleBatch(
                "SUBMITTED-2", java.util.Map.of("dummy", new com.traceability.contracts.SequenceRange(1, 10)), "root13", Instant.now(), AnchorStatus.SUBMITTED,
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

    // ============================================================================
    // COLLECTING RECOVERY TESTS
    // ============================================================================

    @Test
    void findCollectingOlderThan_ignoresRecentBatches() {
        // Arrange: a COLLECTING batch created NOW (within any reasonable timeout)
        MerkleBatch recentBatch = new MerkleBatch(
                "RECENT-COLLECTING", java.util.Map.of("s1", new com.traceability.contracts.SequenceRange(1, 5)),
                null, Instant.now(), AnchorStatus.COLLECTING,
                null, null, null, null, null, null, null, null
        );
        adapter.save(recentBatch);

        // Act: cutoff is 5 minutes ago — recent batch should NOT appear
        Instant cutoff = Instant.now().minusSeconds(300);
        List<MerkleBatch> result = adapter.findCollectingOlderThan(cutoff, 10);

        // Assert
        assertTrue(result.isEmpty(), "Recent COLLECTING batch should NOT be returned");
    }

    @Test
    void findCollectingOlderThan_returnsExpiredBatches() {
        // Arrange: insert a COLLECTING batch document with createdAt set to 10 minutes ago (expired)
        MerkleBatchDocument doc = new MerkleBatchDocument();
        doc.setBatchId("EXPIRED-COLLECTING");
        doc.setStatus(AnchorStatus.COLLECTING);
        doc.setCreatedAt(Instant.now().minusSeconds(600));
        doc.setCoverage(java.util.Map.of("s1", new com.traceability.contracts.SequenceRange(1, 5)));
        mongoTemplate.save(doc);

        // Act: cutoff is 5 minutes ago — the 10-minute-old batch should appear
        Instant cutoff = Instant.now().minusSeconds(300);
        List<MerkleBatch> result = adapter.findCollectingOlderThan(cutoff, 10);

        // Assert
        assertEquals(1, result.size());
        assertEquals("EXPIRED-COLLECTING", result.get(0).batchId());
    }

    @Test
    void findCollectingOlderThan_respectsLimit() {
        // Arrange: create 5 expired COLLECTING batches
        for (int i = 1; i <= 5; i++) {
            MerkleBatchDocument doc = new MerkleBatchDocument();
            doc.setBatchId("LIMIT-BATCH-" + i);
            doc.setStatus(AnchorStatus.COLLECTING);
            doc.setCreatedAt(Instant.now().minusSeconds(600 + i)); // all expired, slightly staggered
            doc.setCoverage(java.util.Map.of("s1", new com.traceability.contracts.SequenceRange(1, 5)));
            mongoTemplate.save(doc);
        }

        // Act: cutoff is 5 minutes ago, but limit is 3
        Instant cutoff = Instant.now().minusSeconds(300);
        List<MerkleBatch> result = adapter.findCollectingOlderThan(cutoff, 3);

        // Assert: only 3 batches, oldest first
        assertEquals(3, result.size(), "Should only return limit count");
        // Verify ordering: oldest first (highest minusSeconds offset)
        assertTrue(result.get(0).createdAt().isBefore(result.get(1).createdAt()),
                "Results should be ordered by createdAt ASC (oldest first)");
    }

    @Test
    void incrementRecoveryAttempts_incrementsAtomically() {
        // Arrange: COLLECTING batch with default recoveryAttempts = 0
        MerkleBatch batch = new MerkleBatch(
                "INC-TEST", java.util.Map.of("s1", new com.traceability.contracts.SequenceRange(1, 5)),
                null, Instant.now(), AnchorStatus.COLLECTING,
                null, null, null, null, null, null, null, null
        );
        adapter.save(batch);

        // Verify initial state
        assertEquals(0, adapter.findByBatchId("INC-TEST").orElseThrow().recoveryAttempts());

        // Act & Assert: first increment → 1
        int first = adapter.incrementRecoveryAttempts("INC-TEST");
        assertEquals(1, first, "First increment should return 1");
        assertEquals(1, adapter.findByBatchId("INC-TEST").orElseThrow().recoveryAttempts());

        // Act & Assert: second increment → 2
        int second = adapter.incrementRecoveryAttempts("INC-TEST");
        assertEquals(2, second, "Second increment should return 2");
        assertEquals(2, adapter.findByBatchId("INC-TEST").orElseThrow().recoveryAttempts());
    }

    @Test
    void incrementRecoveryAttempts_returnsMinusOneIfNotCollecting() {
        // Arrange: PENDING batch (not COLLECTING)
        MerkleBatch batch = new MerkleBatch(
                "NOT-COLLECTING", java.util.Map.of("s1", new com.traceability.contracts.SequenceRange(1, 5)),
                "root", Instant.now(), AnchorStatus.PENDING,
                null, null, null, null, null, null, null, null
        );
        adapter.save(batch);

        // Act
        int result = adapter.incrementRecoveryAttempts("NOT-COLLECTING");

        // Assert: -1 because batch is not in COLLECTING state
        assertEquals(-1, result, "Should return -1 for non-COLLECTING batch");
        // Verify recoveryAttempts was NOT incremented
        assertEquals(0, adapter.findByBatchId("NOT-COLLECTING").orElseThrow().recoveryAttempts());
    }
}
