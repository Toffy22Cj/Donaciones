package com.traceability.app.application.integrity;

import com.traceability.contracts.AssetAnchorStatusPort.StepAnchor;
import com.traceability.contracts.SequenceRange;
import com.traceability.contracts.TrackingAnchorStatus;
import com.traceability.core.application.port.out.StreamEventAnchorDataPort;
import com.traceability.core.application.port.out.StreamEventAnchorDataPort.StreamEventAnchorData;
import com.traceability.crypto.application.port.out.MerkleBatchRepositoryPort;
import com.traceability.crypto.domain.AnchorStatus;
import com.traceability.crypto.domain.MerkleBatch;
import com.traceability.crypto.domain.MerkleTree;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Ficha R-6, G-3: cobertura exhaustiva de los dos {@code enum} (test 9c) y una lectura por lote (plan C, §2.4 paso 4.3). */
class AssetAnchorStatusProducerTest {

    // 9c: cada AnchorStatus tiene fila (el switch no tiene default: un estado nuevo no compila)
    @Test
    void t9c_everyAnchorStatusHasARow() {
        for (AnchorStatus status : AnchorStatus.values()) {
            assertThat(AssetAnchorStatusProducer.trackingStatusOf(status)).as("%s", status).isNotNull();
        }
    }

    // 9c: cada uno de los cuatro valores sale de al menos un AnchorStatus (ninguno sobra)
    @Test
    void t9c_everyTrackingAnchorStatusComesFromAtLeastOneAnchorStatus() {
        Set<TrackingAnchorStatus> produced = Arrays.stream(AnchorStatus.values())
                .map(AssetAnchorStatusProducer::trackingStatusOf)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(TrackingAnchorStatus.class)));
        assertThat(produced).containsExactlyInAnyOrder(TrackingAnchorStatus.values());
        assertThat(TrackingAnchorStatus.values()).hasSize(4);
    }

    // Plan C, §2.4 paso 4.3: varios pasos del mismo lote leen el lote una sola vez
    @Test
    void severalStepsOfTheSameBatch_readTheBatchOnce() {
        StreamEventAnchorDataPort events = mock(StreamEventAnchorDataPort.class);
        MerkleBatchRepositoryPort batches = mock(MerkleBatchRepositoryPort.class);
        List<String> leaves = List.of("h1", "h2", "h3");
        when(events.findByStream("a")).thenReturn(List.of(
                new StreamEventAnchorData(1, "h1", "b"),
                new StreamEventAnchorData(2, "h2", "b"),
                new StreamEventAnchorData(3, "h3", "b")));
        when(batches.findByBatchId("b")).thenReturn(Optional.of(new MerkleBatch("b", Map.of("a", new SequenceRange(1, 3)),
                MerkleTree.build(leaves).getRoot(), leaves, Instant.EPOCH, AnchorStatus.ANCHORED, "net", "0xc", 1L,
                "0xtx", Instant.EPOCH, Instant.EPOCH, 1L, null)));

        Map<Long, StepAnchor> result = new AssetAnchorStatusProducer(events, batches).anchorBySequence("a");

        assertThat(result.values()).allSatisfy(s -> assertThat(s.proof()).isNotNull());
        verify(batches, times(1)).findByBatchId("b");
    }
}
