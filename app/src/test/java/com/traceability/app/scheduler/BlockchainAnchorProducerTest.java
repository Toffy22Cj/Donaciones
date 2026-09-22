package com.traceability.app.scheduler;

import com.traceability.contracts.SequenceRange;
import com.traceability.core.application.port.out.UnanchoredEventRepositoryPort;
import com.traceability.crypto.application.port.out.MerkleBatchRepositoryPort;
import com.traceability.crypto.domain.AnchorStatus;
import com.traceability.crypto.domain.MerkleBatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class BlockchainAnchorProducerTest {

    private UnanchoredEventRepositoryPort unanchoredEventRepositoryPort;
    private MerkleBatchRepositoryPort merkleBatchRepositoryPort;
    private TransactionTemplate transactionTemplate;
    private TransactionStatus transactionStatus;

    private BlockchainAnchorProducer producer;

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

        producer = new BlockchainAnchorProducer(
                unanchoredEventRepositoryPort,
                merkleBatchRepositoryPort,
                transactionTemplate,
                10,
                1000
        );
    }

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
}
