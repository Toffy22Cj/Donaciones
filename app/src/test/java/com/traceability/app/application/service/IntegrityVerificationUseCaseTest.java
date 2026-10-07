package com.traceability.app.application.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.traceability.contracts.SequenceRange;
import com.traceability.core.application.port.out.UnanchoredEventRepositoryPort;
import com.traceability.crypto.application.port.out.MerkleBatchRepositoryPort;
import com.traceability.crypto.domain.AnchorStatus;
import com.traceability.crypto.domain.MerkleBatch;
import com.traceability.crypto.domain.MerkleTree;
import com.traceability.crypto.domain.StreamIdentity;
import com.traceability.crypto.domain.VerificationResult;
import com.traceability.crypto.domain.VerificationStatus;

class IntegrityVerificationUseCaseTest {

    private MerkleBatchRepositoryPort merkleBatchRepositoryPort;
    private UnanchoredEventRepositoryPort unanchoredEventRepositoryPort;
    private IntegrityVerificationUseCase useCase;

    @BeforeEach
    void setUp() {
        merkleBatchRepositoryPort = mock(MerkleBatchRepositoryPort.class);
        unanchoredEventRepositoryPort = mock(UnanchoredEventRepositoryPort.class);
        useCase = new IntegrityVerificationUseCase(merkleBatchRepositoryPort, unanchoredEventRepositoryPort);
    }

    @Test
    void verifyBatch_withOutOfOrderCoverageKeys_expandsIdentitiesInCanonicalOrder() {
        // Arrange
        // Using LinkedHashMap to enforce an insertion order that is NOT alphabetical
        Map<String, SequenceRange> coverage = new LinkedHashMap<>();
        coverage.put("stream-z", new SequenceRange(1, 1));
        coverage.put("stream-a", new SequenceRange(1, 1));
        coverage.put("stream-m", new SequenceRange(1, 1));

        // Let's create original leaf hashes and a tampered version
        String hashA = "hashA";
        String hashM = "hashM";
        String hashZ = "hashZ";
        
        List<String> originalLeaves = List.of(hashA, hashM, hashZ);
        String originalRoot = MerkleTree.build(originalLeaves).getRoot();

        MerkleBatch batch = new MerkleBatch(
                "batch-1", coverage, originalRoot, originalLeaves, Instant.now(), AnchorStatus.ANCHORED,
                "net", "addr", 1L, "tx", Instant.now(), Instant.now(), 123L, null
        );

        when(merkleBatchRepositoryPort.findByBatchId("batch-1")).thenReturn(Optional.of(batch));

        // The current database returns hashM modified
        String tamperedHashM = "tamperedM";
        List<String> currentLeavesFromDb = List.of(hashA, tamperedHashM, hashZ);
        when(unanchoredEventRepositoryPort.getEventHashesByCoverage(coverage)).thenReturn(currentLeavesFromDb);

        // Act
        VerificationResult result = useCase.verifyBatch("batch-1");

        // Assert
        assertThat(result.status()).isEqualTo(VerificationStatus.MISMATCH);
        assertThat(result.diagnosisComplete()).isTrue();
        
        // The affected sequence must be exactly stream-m at sequence 1,
        // proving that the coverage map was sorted alphabetically during expansion.
        assertThat(result.affectedSequences()).hasSize(1);
        assertThat(result.affectedSequences().get(0)).isEqualTo(new StreamIdentity("stream-m", 1));
    }

    @Test
    void verifyAllAnchored_evaluatesLazily() {
        // Arrange
        MerkleBatch batch1 = new MerkleBatch("b1", Map.of("s1", new SequenceRange(1, 1)), "r1", List.of("h1"), Instant.now(), AnchorStatus.ANCHORED, "net", "addr", 1L, "tx", Instant.now(), Instant.now(), 123L, null);
        MerkleBatch batch2 = new MerkleBatch("b2", Map.of("s2", new SequenceRange(1, 1)), "r2", List.of("h2"), Instant.now(), AnchorStatus.ANCHORED, "net", "addr", 2L, "tx", Instant.now(), Instant.now(), 123L, null);
        
        java.util.concurrent.atomic.AtomicInteger counter = new java.util.concurrent.atomic.AtomicInteger(0);
        
        java.util.stream.Stream<MerkleBatch> spyStream = java.util.stream.Stream.of(batch1, batch2)
                .peek(b -> counter.incrementAndGet());
                
        when(merkleBatchRepositoryPort.streamByStatus(AnchorStatus.ANCHORED)).thenReturn(spyStream);
        when(unanchoredEventRepositoryPort.getEventHashesByCoverage(any())).thenReturn(List.of("h1"));

        // Act
        try (java.util.stream.Stream<VerificationResult> resultStream = useCase.verifyAllAnchored()) {
            Optional<VerificationResult> first = resultStream.limit(1).findFirst();
            
            // Assert
            assertThat(first).isPresent();
            assertThat(counter.get()).isEqualTo(1); // Only the first batch should have been processed
            verify(merkleBatchRepositoryPort, never()).findByBatchId(any());
        }
    }

    @Test
    void verifyAllAnchored_closesSourceStream() {
        // Arrange
        MerkleBatch batch = new MerkleBatch("b1", Map.of("s1", new SequenceRange(1, 1)), "r1", List.of("h1"), Instant.now(), AnchorStatus.ANCHORED, "net", "addr", 1L, "tx", Instant.now(), Instant.now(), 123L, null);
        
        boolean[] isClosed = {false};
        java.util.stream.Stream<MerkleBatch> stream = java.util.stream.Stream.of(batch).onClose(() -> isClosed[0] = true);
        when(merkleBatchRepositoryPort.streamByStatus(AnchorStatus.ANCHORED)).thenReturn(stream);
        
        // Act
        java.util.stream.Stream<VerificationResult> resultStream = useCase.verifyAllAnchored();
        resultStream.close();
        
        // Assert
        assertThat(isClosed[0]).isTrue();
    }

    @Test
    void verifyAllAnchored_propagatesLegacyExceptionNaturally() {
        // Arrange
        MerkleBatch validBatch1 = new MerkleBatch("b1", Map.of("s1", new SequenceRange(1, 1)), "r1", List.of("h1"), Instant.now(), AnchorStatus.ANCHORED, "net", "addr", 1L, "tx", Instant.now(), Instant.now(), 123L, null);
        MerkleBatch legacyBatch = new MerkleBatch("legacy", Map.of("s2", new SequenceRange(1, 1)), "r2", null, Instant.now(), AnchorStatus.ANCHORED, "net", "addr", 2L, "tx", Instant.now(), Instant.now(), 123L, null);
        MerkleBatch validBatch2 = new MerkleBatch("b3", Map.of("s3", new SequenceRange(1, 1)), "r3", List.of("h3"), Instant.now(), AnchorStatus.ANCHORED, "net", "addr", 3L, "tx", Instant.now(), Instant.now(), 123L, null);
        
        when(merkleBatchRepositoryPort.streamByStatus(AnchorStatus.ANCHORED))
                .thenReturn(java.util.stream.Stream.of(validBatch1, legacyBatch, validBatch2));
                
        when(merkleBatchRepositoryPort.findByBatchId("b1")).thenReturn(Optional.of(validBatch1));
        when(merkleBatchRepositoryPort.findByBatchId("legacy")).thenReturn(Optional.of(legacyBatch));
        when(merkleBatchRepositoryPort.findByBatchId("b3")).thenReturn(Optional.of(validBatch2));
        
        when(unanchoredEventRepositoryPort.getEventHashesByCoverage(any())).thenReturn(List.of("h1")); 

        // Act & Assert
        try (java.util.stream.Stream<VerificationResult> stream = useCase.verifyAllAnchored()) {
            java.util.Iterator<VerificationResult> iterator = stream.iterator();
            
            // First valid batch is processed successfully
            assertThat(iterator.hasNext()).isTrue();
            VerificationResult res1 = iterator.next();
            assertThat(res1).isNotNull();
            
            // Second batch is legacy, should throw (hasNext() or next() may trigger the lazy evaluation)
            org.junit.jupiter.api.Assertions.assertThrows(com.traceability.crypto.domain.exception.LegacyBatchLeafHashesUnavailableException.class, () -> {
                if (iterator.hasNext()) {
                    iterator.next();
                }
            });
        }
    }
}
