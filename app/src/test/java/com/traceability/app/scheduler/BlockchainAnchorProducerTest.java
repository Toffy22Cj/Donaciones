package com.traceability.app.scheduler;

import com.traceability.contracts.SequenceRange;
import com.traceability.core.application.port.out.UnanchoredEventRepositoryPort;
import com.traceability.crypto.application.port.out.MerkleBatchRepositoryPort;
import com.traceability.crypto.domain.AnchorStatus;
import com.traceability.crypto.domain.MerkleBatch;
import com.traceability.crypto.domain.MerkleTree;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

class BlockchainAnchorProducerTest {

    private UnanchoredEventRepositoryPort unanchoredEventRepositoryPort;
    private MerkleBatchRepositoryPort merkleBatchRepositoryPort;
    private TransactionTemplate transactionTemplate;
    private TransactionStatus transactionStatus;

    private BlockchainAnchorProducer producer;

    // Log capture
    private ListAppender<ILoggingEvent> logAppender;
    private Logger producerLogger;

    @BeforeEach
    void setUp() {
        unanchoredEventRepositoryPort = mock(UnanchoredEventRepositoryPort.class);
        merkleBatchRepositoryPort = mock(MerkleBatchRepositoryPort.class);
        transactionTemplate = mock(TransactionTemplate.class);
        transactionStatus = mock(TransactionStatus.class);

        // Mock TransactionTemplate to immediately execute the callback
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(transactionStatus);
        });

        // Default: no stale batches
        when(merkleBatchRepositoryPort.findCollectingOlderThan(any(Instant.class), anyInt()))
                .thenReturn(List.of());

        producer = new BlockchainAnchorProducer(
                unanchoredEventRepositoryPort,
                merkleBatchRepositoryPort,
                transactionTemplate,
                10,    // maxStreamsPerBatch
                1000,  // maxEventsPerBatch
                300,   // collectingRecoveryTimeoutSeconds
                10,    // collectingRecoveryMaxPerCycle
                5      // collectingRecoveryWarnAfterAttempts
        );

        // Set up log capture
        producerLogger = (Logger) LoggerFactory.getLogger(BlockchainAnchorProducer.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        producerLogger.addAppender(logAppender);
    }

    // ============================================================================
    // EXISTING TESTS (unchanged behavior)
    // ============================================================================

    @Test
    void shouldNotProceedToPhase2_WhenPhase1ReturnsEmptyCoverage() {
        // Given
        when(unanchoredEventRepositoryPort.claimOrphansAndAssignBatch(anyString(), anyInt(), anyInt()))
                .thenReturn(Map.of());

        // When
        producer.produceBatch();

        // Then
        verify(transactionStatus).setRollbackOnly();
        verify(merkleBatchRepositoryPort, never()).save(any(MerkleBatch.class));
        verify(unanchoredEventRepositoryPort, never()).getEventHashesByCoverage(any());
        verify(merkleBatchRepositoryPort, never()).transitionCollectingToPending(anyString(), anyString(), anyList());
    }

    @Test
    void shouldCompleteAllPhases_WhenEventsAreClaimed() {
        // Given
        Map<String, SequenceRange> coverage = Map.of("streamA", new SequenceRange(1, 10));
        when(unanchoredEventRepositoryPort.claimOrphansAndAssignBatch(anyString(), eq(10), eq(1000)))
                .thenReturn(coverage);

        when(unanchoredEventRepositoryPort.getEventHashesByCoverage(coverage))
                .thenReturn(List.of("hash1", "hash2")); // leaves to build the tree

        when(merkleBatchRepositoryPort.transitionCollectingToPending(anyString(), anyString(), anyList()))
                .thenReturn(true);

        // When
        producer.produceBatch();

        // Then
        // 1. Verify Phase 1 (save with COLLECTING status)
        ArgumentCaptor<MerkleBatch> batchCaptor = ArgumentCaptor.forClass(MerkleBatch.class);
        verify(merkleBatchRepositoryPort).save(batchCaptor.capture());
        MerkleBatch savedBatch = batchCaptor.getValue();
        
        assertThat(savedBatch.status()).isEqualTo(AnchorStatus.COLLECTING);
        assertThat(savedBatch.coverage()).isEqualTo(coverage);
        
        String batchId = savedBatch.batchId();

        // 2. Verify Phase 2 (fetch hashes and compute Merkle Tree)
        verify(unanchoredEventRepositoryPort).getEventHashesByCoverage(coverage);

        // 3. Verify Phase 3 (transition)
        ArgumentCaptor<String> rootCaptor = ArgumentCaptor.forClass(String.class);
        verify(merkleBatchRepositoryPort).transitionCollectingToPending(eq(batchId), rootCaptor.capture(), anyList());
        assertThat(rootCaptor.getValue()).isNotBlank();
    }

    // ============================================================================
    // COLLECTING RECOVERY TESTS
    // ============================================================================

    @Test
    void shouldRecoverExpiredCollectingBatch() {
        // Given: a stale COLLECTING batch
        Map<String, SequenceRange> coverage = Map.of("streamA", new SequenceRange(1, 10));
        MerkleBatch staleBatch = new MerkleBatch(
                "STALE-BATCH-1", coverage, null, Instant.now().minusSeconds(600), AnchorStatus.COLLECTING,
                null, null, null, null, null, null, null, null
        );

        when(merkleBatchRepositoryPort.findCollectingOlderThan(any(Instant.class), eq(10)))
                .thenReturn(List.of(staleBatch));
        when(merkleBatchRepositoryPort.incrementRecoveryAttempts("STALE-BATCH-1"))
                .thenReturn(1);

        List<String> leafHashes = List.of("hash1", "hash2");
        when(unanchoredEventRepositoryPort.getEventHashesByCoverage(coverage))
                .thenReturn(leafHashes);

        // Compute expected merkle root deterministically
        String expectedRoot = MerkleTree.build(leafHashes).getRoot();

        when(merkleBatchRepositoryPort.transitionCollectingToPending(eq("STALE-BATCH-1"), eq(expectedRoot), eq(leafHashes)))
                .thenReturn(true);

        // Also set up Phase 1 to return empty (no new orphans)
        when(unanchoredEventRepositoryPort.claimOrphansAndAssignBatch(anyString(), anyInt(), anyInt()))
                .thenReturn(Map.of());

        // When
        producer.produceBatch();

        // Then: recovery happened with correct deterministic values
        verify(merkleBatchRepositoryPort).incrementRecoveryAttempts("STALE-BATCH-1");
        verify(unanchoredEventRepositoryPort).getEventHashesByCoverage(coverage);
        verify(merkleBatchRepositoryPort).transitionCollectingToPending("STALE-BATCH-1", expectedRoot, leafHashes);
    }

    @Test
    void shouldNotRecoverRecentCollectingBatch() {
        // Given: findCollectingOlderThan returns empty (all batches are recent)
        when(merkleBatchRepositoryPort.findCollectingOlderThan(any(Instant.class), anyInt()))
                .thenReturn(List.of());

        when(unanchoredEventRepositoryPort.claimOrphansAndAssignBatch(anyString(), anyInt(), anyInt()))
                .thenReturn(Map.of());

        // When
        producer.produceBatch();

        // Then: no recovery methods called
        verify(merkleBatchRepositoryPort, never()).incrementRecoveryAttempts(anyString());
    }

    @Test
    void shouldRespectMaxPerCycleLimit() {
        // Given: findCollectingOlderThan is called with limit = 10 (configured max-per-cycle)
        when(merkleBatchRepositoryPort.findCollectingOlderThan(any(Instant.class), eq(10)))
                .thenReturn(List.of());

        when(unanchoredEventRepositoryPort.claimOrphansAndAssignBatch(anyString(), anyInt(), anyInt()))
                .thenReturn(Map.of());

        // When
        producer.produceBatch();

        // Then: verify the limit parameter was correctly passed
        verify(merkleBatchRepositoryPort).findCollectingOlderThan(any(Instant.class), eq(10));
    }

    @Test
    void shouldWarnAfterExcessiveAttempts() {
        // Given: a stale batch that has been recovered many times
        Map<String, SequenceRange> coverage = Map.of("s1", new SequenceRange(1, 5));
        MerkleBatch staleBatch = new MerkleBatch(
                "WARN-BATCH", coverage, null, Instant.now().minusSeconds(600), AnchorStatus.COLLECTING,
                null, null, null, null, null, null, null, null
        );

        when(merkleBatchRepositoryPort.findCollectingOlderThan(any(Instant.class), anyInt()))
                .thenReturn(List.of(staleBatch));
        // Return 6 (exceeds warn-after-attempts=5)
        when(merkleBatchRepositoryPort.incrementRecoveryAttempts("WARN-BATCH"))
                .thenReturn(6);
        when(unanchoredEventRepositoryPort.getEventHashesByCoverage(coverage))
                .thenReturn(List.of("hash1"));
        when(merkleBatchRepositoryPort.transitionCollectingToPending(anyString(), anyString(), anyList()))
                .thenReturn(true);
        when(unanchoredEventRepositoryPort.claimOrphansAndAssignBatch(anyString(), anyInt(), anyInt()))
                .thenReturn(Map.of());

        // When
        producer.produceBatch();

        // Then: verify WARN log was emitted
        boolean foundWarn = logAppender.list.stream()
                .anyMatch(event -> event.getLevel() == Level.WARN
                        && event.getFormattedMessage().contains("WARN-BATCH")
                        && event.getFormattedMessage().contains("6 times"));
        assertThat(foundWarn).as("Expected WARN log about excessive recovery attempts for WARN-BATCH").isTrue();
    }

    @Test
    void shouldRecoverBeforeClaimingNewOrphans() {
        // Given
        when(merkleBatchRepositoryPort.findCollectingOlderThan(any(Instant.class), anyInt()))
                .thenReturn(List.of()); // no stale batches, but we verify the order
        when(unanchoredEventRepositoryPort.claimOrphansAndAssignBatch(anyString(), anyInt(), anyInt()))
                .thenReturn(Map.of());

        // When
        producer.produceBatch();

        // Then: verify order — findCollectingOlderThan BEFORE claimOrphansAndAssignBatch
        InOrder inOrder = inOrder(merkleBatchRepositoryPort, unanchoredEventRepositoryPort);
        inOrder.verify(merkleBatchRepositoryPort).findCollectingOlderThan(any(Instant.class), anyInt());
        inOrder.verify(unanchoredEventRepositoryPort).claimOrphansAndAssignBatch(anyString(), anyInt(), anyInt());
    }

    @Test
    void shouldHandleTransitionReturnsFalseGracefully() {
        // Given: a stale batch where transitionCollectingToPending returns false (already done)
        Map<String, SequenceRange> coverage = Map.of("s1", new SequenceRange(1, 5));
        MerkleBatch staleBatch = new MerkleBatch(
                "ALREADY-DONE", coverage, null, Instant.now().minusSeconds(600), AnchorStatus.COLLECTING,
                null, null, null, null, null, null, null, null
        );

        when(merkleBatchRepositoryPort.findCollectingOlderThan(any(Instant.class), anyInt()))
                .thenReturn(List.of(staleBatch));
        when(merkleBatchRepositoryPort.incrementRecoveryAttempts("ALREADY-DONE"))
                .thenReturn(1);
        when(unanchoredEventRepositoryPort.getEventHashesByCoverage(coverage))
                .thenReturn(List.of("hash1"));
        when(merkleBatchRepositoryPort.transitionCollectingToPending(anyString(), anyString(), anyList()))
                .thenReturn(false);
        when(unanchoredEventRepositoryPort.claimOrphansAndAssignBatch(anyString(), anyInt(), anyInt()))
                .thenReturn(Map.of());

        // When
        producer.produceBatch();

        // Then: no exception, INFO log about benign no-op
        boolean foundInfo = logAppender.list.stream()
                .anyMatch(event -> event.getLevel() == Level.INFO
                        && event.getFormattedMessage().contains("ALREADY-DONE")
                        && event.getFormattedMessage().contains("benign no-op"));
        assertThat(foundInfo).as("Expected INFO log about benign no-op for ALREADY-DONE").isTrue();
    }

    @Test
    void shouldContinueRecoveryWhenOneBatchFails() {
        // Given: two stale batches, first one fails during recovery, second should still be processed
        Map<String, SequenceRange> coverage1 = Map.of("s1", new SequenceRange(1, 5));
        Map<String, SequenceRange> coverage2 = Map.of("s2", new SequenceRange(1, 10));

        MerkleBatch failingBatch = new MerkleBatch(
                "FAILING-BATCH", coverage1, null, Instant.now().minusSeconds(700), AnchorStatus.COLLECTING,
                null, null, null, null, null, null, null, null
        );
        MerkleBatch healthyBatch = new MerkleBatch(
                "HEALTHY-BATCH", coverage2, null, Instant.now().minusSeconds(600), AnchorStatus.COLLECTING,
                null, null, null, null, null, null, null, null
        );

        when(merkleBatchRepositoryPort.findCollectingOlderThan(any(Instant.class), anyInt()))
                .thenReturn(List.of(failingBatch, healthyBatch));

        // FAILING-BATCH: incrementRecoveryAttempts succeeds, but getEventHashesByCoverage throws
        when(merkleBatchRepositoryPort.incrementRecoveryAttempts("FAILING-BATCH"))
                .thenReturn(1);
        when(unanchoredEventRepositoryPort.getEventHashesByCoverage(coverage1))
                .thenThrow(new RuntimeException("Simulated infrastructure failure"));

        // HEALTHY-BATCH: everything works
        when(merkleBatchRepositoryPort.incrementRecoveryAttempts("HEALTHY-BATCH"))
                .thenReturn(1);
        when(unanchoredEventRepositoryPort.getEventHashesByCoverage(coverage2))
                .thenReturn(List.of("hash2"));
        when(merkleBatchRepositoryPort.transitionCollectingToPending(eq("HEALTHY-BATCH"), anyString(), anyList()))
                .thenReturn(true);

        // Phase 1: no new orphans
        when(unanchoredEventRepositoryPort.claimOrphansAndAssignBatch(anyString(), anyInt(), anyInt()))
                .thenReturn(Map.of());

        // When
        producer.produceBatch();

        // Then:
        // 1. Failing batch was attempted and error logged
        boolean foundError = logAppender.list.stream()
                .anyMatch(event -> event.getLevel() == Level.ERROR
                        && event.getFormattedMessage().contains("FAILING-BATCH")
                        && event.getFormattedMessage().contains("Skipping"));
        assertThat(foundError).as("Expected ERROR log about FAILING-BATCH").isTrue();

        // 2. Healthy batch was processed successfully despite failing batch
        verify(merkleBatchRepositoryPort).transitionCollectingToPending(eq("HEALTHY-BATCH"), anyString(), anyList());

        // 3. Phase 1 still ran after recovery
        verify(unanchoredEventRepositoryPort).claimOrphansAndAssignBatch(anyString(), anyInt(), anyInt());
    }

    @Test
    void shouldContinueRecoveryWhenPhase3Fails() {
        // Given: two stale batches, first one fails during Phase 3, second should still be processed
        Map<String, SequenceRange> coverage1 = Map.of("s1", new SequenceRange(1, 5));
        Map<String, SequenceRange> coverage2 = Map.of("s2", new SequenceRange(1, 10));

        MerkleBatch failingBatch = new MerkleBatch(
                "FAILING-PHASE3-BATCH", coverage1, null, Instant.now().minusSeconds(700), AnchorStatus.COLLECTING,
                null, null, null, null, null, null, null, null
        );
        MerkleBatch healthyBatch = new MerkleBatch(
                "HEALTHY-BATCH-2", coverage2, null, Instant.now().minusSeconds(600), AnchorStatus.COLLECTING,
                null, null, null, null, null, null, null, null
        );

        when(merkleBatchRepositoryPort.findCollectingOlderThan(any(Instant.class), anyInt()))
                .thenReturn(List.of(failingBatch, healthyBatch));

        // FAILING-PHASE3-BATCH: increment succeeds, Phase 2 succeeds, Phase 3 throws
        when(merkleBatchRepositoryPort.incrementRecoveryAttempts("FAILING-PHASE3-BATCH"))
                .thenReturn(1);
        when(unanchoredEventRepositoryPort.getEventHashesByCoverage(coverage1))
                .thenReturn(List.of("hash1"));
        when(merkleBatchRepositoryPort.transitionCollectingToPending(eq("FAILING-PHASE3-BATCH"), anyString(), anyList()))
                .thenThrow(new RuntimeException("Simulated Phase 3 failure"));

        // HEALTHY-BATCH-2: everything works
        when(merkleBatchRepositoryPort.incrementRecoveryAttempts("HEALTHY-BATCH-2"))
                .thenReturn(1);
        when(unanchoredEventRepositoryPort.getEventHashesByCoverage(coverage2))
                .thenReturn(List.of("hash2"));
        when(merkleBatchRepositoryPort.transitionCollectingToPending(eq("HEALTHY-BATCH-2"), anyString(), anyList()))
                .thenReturn(true);

        // Phase 1: no new orphans
        when(unanchoredEventRepositoryPort.claimOrphansAndAssignBatch(anyString(), anyInt(), anyInt()))
                .thenReturn(Map.of());

        // When
        producer.produceBatch();

        // Then:
        // 1. Failing batch was attempted and error logged
        boolean foundError = logAppender.list.stream()
                .anyMatch(event -> event.getLevel() == Level.ERROR
                        && event.getFormattedMessage().contains("FAILING-PHASE3-BATCH")
                        && event.getFormattedMessage().contains("Skipping"));
        assertThat(foundError).as("Expected ERROR log about FAILING-PHASE3-BATCH").isTrue();

        // 2. Healthy batch was processed successfully despite failing batch
        verify(merkleBatchRepositoryPort).transitionCollectingToPending(eq("HEALTHY-BATCH-2"), anyString(), anyList());

        // 3. Phase 1 still ran after recovery
        verify(unanchoredEventRepositoryPort).claimOrphansAndAssignBatch(anyString(), anyInt(), anyInt());
    }
}
