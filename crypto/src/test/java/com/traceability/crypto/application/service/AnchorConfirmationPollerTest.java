package com.traceability.crypto.application.service;

import com.traceability.crypto.application.port.out.BlockchainAnchorRepositoryPort;
import com.traceability.crypto.application.port.out.BlockchainTransactionReceiptPort;
import com.traceability.crypto.domain.AnchorStatus;
import com.traceability.crypto.domain.MerkleBatch;
import com.traceability.crypto.domain.TransactionConfirmation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.web3j.utils.Numeric;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AnchorConfirmationPollerTest {

    @Mock
    private BlockchainAnchorRepositoryPort repositoryPort;

    @Mock
    private BlockchainTransactionReceiptPort receiptPort;

    @InjectMocks
    private AnchorConfirmationPoller poller;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(poller, "confirmationsRequired", 12L);
        ReflectionTestUtils.setField(poller, "receiptTimeoutSeconds", 3600L); // 1 hour
    }

    @Test
    void shouldTransitionToAnchoredWhenReceiptIsSuccessfulWithEnoughConfirmationsAndExactRootMatch() {
        // Arrange
        String validRootHex = "0x1111111111111111111111111111111111111111111111111111111111111111";
        MerkleBatch batch = new MerkleBatch("batch-1", 1, 10, validRootHex, Instant.now().minusSeconds(100), AnchorStatus.SUBMITTED,
                "network", "0xContract", 1L, "0xHash", Instant.now().minusSeconds(60), null, null, null);
        
        when(repositoryPort.findSubmittedOlderFirst()).thenReturn(List.of(batch));
        
        // Receipt with status 1 (not reverted), block 1000, and matching root
        byte[] expectedBytes = Numeric.hexStringToByteArray(validRootHex);
        TransactionConfirmation confirmation = new TransactionConfirmation(false, 1000L, expectedBytes);
        when(receiptPort.getTransactionConfirmation("0xHash", "0xContract")).thenReturn(Optional.of(confirmation));
        
        // Current block is 1012, so confirmations = 1012 - 1000 = 12 (meets requirement of 12)
        when(receiptPort.getCurrentBlockNumber()).thenReturn(1012L);

        // Act
        poller.pollSubmittedBatches();

        // Assert
        verify(repositoryPort).markAnchored(eq("batch-1"), eq(1000L), any(Instant.class));
        verify(repositoryPort, never()).markAnchorMismatch(anyString());
        verify(repositoryPort, never()).markFailed(anyString());
        verify(repositoryPort, never()).markStuck(anyString());
    }

    @Test
    void shouldTransitionToAnchorMismatchWhenRootBytesDiffer() {
        // Arrange
        String validRootHex = "0x1111111111111111111111111111111111111111111111111111111111111111";
        MerkleBatch batch = new MerkleBatch("batch-2", 1, 10, validRootHex, Instant.now().minusSeconds(100), AnchorStatus.SUBMITTED,
                "network", "0xContract", 1L, "0xHash", Instant.now().minusSeconds(60), null, null, null);
        
        when(repositoryPort.findSubmittedOlderFirst()).thenReturn(List.of(batch));
        
        // Receipt with status 1 but INCORRECT root bytes
        byte[] wrongBytes = Numeric.hexStringToByteArray("0x2222222222222222222222222222222222222222222222222222222222222222");
        TransactionConfirmation confirmation = new TransactionConfirmation(false, 1000L, wrongBytes);
        when(receiptPort.getTransactionConfirmation("0xHash", "0xContract")).thenReturn(Optional.of(confirmation));
        when(receiptPort.getCurrentBlockNumber()).thenReturn(1012L);

        // Act
        poller.pollSubmittedBatches();

        // Assert
        verify(repositoryPort).markAnchorMismatch("batch-2");
        verify(repositoryPort, never()).markAnchored(anyString(), anyLong(), any(Instant.class));
        verify(repositoryPort, never()).markFailed(anyString());
        verify(repositoryPort, never()).markStuck(anyString());
    }

    @Test
    void shouldTransitionToFailedWhenReceiptStatusIsReverted() {
        // Arrange
        MerkleBatch batch = new MerkleBatch("batch-3", 1, 10, "0x123", Instant.now().minusSeconds(100), AnchorStatus.SUBMITTED,
                "network", "0xContract", 1L, "0xHash", Instant.now().minusSeconds(60), null, null, null);
        
        when(repositoryPort.findSubmittedOlderFirst()).thenReturn(List.of(batch));
        
        // Receipt with status 0 (reverted). Notice block and root don't matter much.
        TransactionConfirmation confirmation = new TransactionConfirmation(true, 1000L, null);
        when(receiptPort.getTransactionConfirmation("0xHash", "0xContract")).thenReturn(Optional.of(confirmation));

        // Act
        poller.pollSubmittedBatches();

        // Assert
        verify(repositoryPort).markFailed("batch-3");
        verify(repositoryPort, never()).markAnchored(anyString(), anyLong(), any(Instant.class));
        verify(repositoryPort, never()).markAnchorMismatch(anyString());
        verify(repositoryPort, never()).markStuck(anyString());
        // getCurrentBlockNumber shouldn't even be called for reverted txs
        verify(receiptPort, never()).getCurrentBlockNumber();
    }

    @Test
    void shouldTransitionToStuckWhenNoReceiptIsFoundAfterTimeout() {
        // Arrange
        // Submitted 2 hours ago (timeout is 1 hour)
        Instant submittedAt = Instant.now().minusSeconds(7200);
        MerkleBatch batch = new MerkleBatch("batch-4", 1, 10, "0x123", Instant.now().minusSeconds(8000), AnchorStatus.SUBMITTED,
                "network", "0xContract", 1L, "0xHash", submittedAt, null, null, null);
        
        when(repositoryPort.findSubmittedOlderFirst()).thenReturn(List.of(batch));
        
        // No receipt found yet because it's not even in getTransactionConfirmation call.
        // Actually, the hasTimedOut logic triggers BEFORE calling getTransactionConfirmation.

        // Act
        poller.pollSubmittedBatches();

        // Assert
        verify(repositoryPort).markStuck("batch-4");
        verify(repositoryPort, never()).markAnchored(anyString(), anyLong(), any(Instant.class));
        verify(repositoryPort, never()).markAnchorMismatch(anyString());
        verify(repositoryPort, never()).markFailed(anyString());
        verify(receiptPort, never()).getTransactionConfirmation(anyString(), anyString());
    }

    @Test
    void shouldDoNothingWhenReceiptExistsButConfirmationsAreInsufficient() {
        // Arrange
        String validRootHex = "0x1111111111111111111111111111111111111111111111111111111111111111";
        MerkleBatch batch = new MerkleBatch("batch-5", 1, 10, validRootHex, Instant.now().minusSeconds(100), AnchorStatus.SUBMITTED,
                "network", "0xContract", 1L, "0xHash", Instant.now().minusSeconds(60), null, null, null);
        
        when(repositoryPort.findSubmittedOlderFirst()).thenReturn(List.of(batch));
        
        byte[] expectedBytes = Numeric.hexStringToByteArray(validRootHex);
        TransactionConfirmation confirmation = new TransactionConfirmation(false, 1000L, expectedBytes);
        when(receiptPort.getTransactionConfirmation("0xHash", "0xContract")).thenReturn(Optional.of(confirmation));
        
        // Current block is 1010, so confirmations = 1010 - 1000 = 10 (less than 12)
        when(receiptPort.getCurrentBlockNumber()).thenReturn(1010L);

        // Act
        poller.pollSubmittedBatches();

        // Assert
        verify(repositoryPort, never()).markAnchored(anyString(), anyLong(), any(Instant.class));
        verify(repositoryPort, never()).markAnchorMismatch(anyString());
        verify(repositoryPort, never()).markFailed(anyString());
        verify(repositoryPort, never()).markStuck(anyString());
    }
}
