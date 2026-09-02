package com.traceability.crypto.application.service;

import com.traceability.crypto.application.port.out.BlockchainAnchorRepositoryPort;
import com.traceability.crypto.application.port.out.BlockchainAnchorSubmitterPort;
import com.traceability.crypto.domain.AnchorStatus;
import com.traceability.crypto.domain.MerkleBatch;
import com.traceability.crypto.domain.exception.BlockchainNodeCommunicationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BlockchainAnchorSchedulerTest {

    @Mock
    private BlockchainAnchorRepositoryPort repositoryPort;

    @Mock
    private BlockchainAnchorSubmitterPort submitterPort;

    @InjectMocks
    private BlockchainAnchorScheduler scheduler;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(scheduler, "network", "test-network");
        ReflectionTestUtils.setField(scheduler, "smartContractAddress", "0xabc");
    }

    @Test
    void priorityGuardMustBlockPendingClaimsWhenASubmittingBatchIsStuck() {
        // Arrange
        // Batch A is stuck in SUBMITTING without a txHash
        MerkleBatch stuckBatchA = new MerkleBatch(
                "batch-A", 1, 10, "0x123", Instant.now(), AnchorStatus.SUBMITTING,
                "test-network", "0xabc", 42L, null, null, null, null, null
        );
        
        when(repositoryPort.findSubmittingWithoutTxHashAndNonce())
                .thenReturn(Optional.of(stuckBatchA));

        // Act
        scheduler.processNextBatch();

        // Assert
        // 1. The priority guard must have used Batch A for submission
        verify(submitterPort).submitBatch(stuckBatchA);
        
        // 2. Crucially, the scheduler MUST NOT even attempt to claim a new pending batch
        verify(repositoryPort, never()).claimNextPendingBatchAndAssignNonceWithRetry(anyString(), anyString());
    }

    @Test
    void shouldClaimNewPendingBatchWhenPriorityGuardIsEmpty() {
        // Arrange
        // No batch is stuck
        when(repositoryPort.findSubmittingWithoutTxHashAndNonce())
                .thenReturn(Optional.empty());

        MerkleBatch newBatchB = new MerkleBatch(
                "batch-B", 11, 20, "0x456", Instant.now(), AnchorStatus.SUBMITTING,
                "test-network", "0xabc", 43L, null, null, null, null, null
        );

        when(repositoryPort.claimNextPendingBatchAndAssignNonceWithRetry("test-network", "0xabc"))
                .thenReturn(Optional.of(newBatchB));

        // Act
        scheduler.processNextBatch();

        // Assert
        // 1. Priority guard checked and passed
        verify(repositoryPort).findSubmittingWithoutTxHashAndNonce();
        
        // 2. It successfully claimed the new batch and submitted it
        verify(repositoryPort).claimNextPendingBatchAndAssignNonceWithRetry("test-network", "0xabc");
        verify(submitterPort).submitBatch(newBatchB);
    }

    @Test
    void shouldUpdateToSubmittedOnSuccessfulSubmit() {
        // Arrange
        MerkleBatch batch = new MerkleBatch("batch-C", 1, 10, "0x123", Instant.now(), AnchorStatus.SUBMITTING, "test-network", "0xabc", 1L, null, null, null, null, null);
        when(repositoryPort.findSubmittingWithoutTxHashAndNonce()).thenReturn(Optional.of(batch));
        when(submitterPort.submitBatch(batch)).thenReturn("0xtxhash123");

        // Act
        scheduler.processNextBatch();

        // Assert
        verify(repositoryPort).updateSubmitted(eq("batch-C"), eq("0xtxhash123"), any(Instant.class));
        verify(repositoryPort, never()).keepSubmittingWithSameNonce(anyString());
        verify(repositoryPort, never()).reconcileSubmittingTimeout(anyString(), anyLong());
    }

    @Test
    void shouldKeepSubmittingWithSameNonceOnDeterministicFailure() {
        // Arrange
        MerkleBatch batch = new MerkleBatch("batch-D", 1, 10, "0x123", Instant.now(), AnchorStatus.SUBMITTING, "test-network", "0xabc", 2L, null, null, null, null, null);
        when(repositoryPort.findSubmittingWithoutTxHashAndNonce()).thenReturn(Optional.of(batch));
        when(submitterPort.submitBatch(batch)).thenThrow(new BlockchainNodeCommunicationException("Deterministic Node Check Failed", new RuntimeException()));

        // Act
        scheduler.processNextBatch();

        // Assert
        verify(repositoryPort).keepSubmittingWithSameNonce("batch-D");
        verify(repositoryPort, never()).updateSubmitted(anyString(), anyString(), any(Instant.class));
        verify(repositoryPort, never()).reconcileSubmittingTimeout(anyString(), anyLong());
    }

    @Test
    void shouldReconcileSubmittingTimeoutOnAmbiguousFailure() {
        // Arrange
        MerkleBatch batch = new MerkleBatch("batch-E", 1, 10, "0x123", Instant.now(), AnchorStatus.SUBMITTING, "test-network", "0xabc", 3L, null, null, null, null, null);
        when(repositoryPort.findSubmittingWithoutTxHashAndNonce()).thenReturn(Optional.of(batch));
        when(submitterPort.submitBatch(batch)).thenThrow(new com.traceability.crypto.domain.exception.BlockchainAnchorTimeoutException("Ambiguous Timeout", new RuntimeException()));

        // Act
        scheduler.processNextBatch();

        // Assert
        verify(repositoryPort).reconcileSubmittingTimeout("batch-E", 3L);
        verify(repositoryPort, never()).updateSubmitted(anyString(), anyString(), any(Instant.class));
        verify(repositoryPort, never()).keepSubmittingWithSameNonce(anyString());
    }
}
