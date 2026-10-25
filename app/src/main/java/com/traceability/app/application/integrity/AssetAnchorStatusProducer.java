package com.traceability.app.application.integrity;

import com.traceability.contracts.AssetAnchorStatusPort;
import com.traceability.contracts.SequenceRange;
import com.traceability.contracts.TrackingAnchorStatus;
import com.traceability.core.application.port.out.StreamEventAnchorDataPort;
import com.traceability.core.application.port.out.StreamEventAnchorDataPort.StreamEventAnchorData;
import com.traceability.crypto.application.port.out.MerkleBatchRepositoryPort;
import com.traceability.crypto.domain.AnchorStatus;
import com.traceability.crypto.domain.MerkleBatch;
import com.traceability.crypto.domain.MerkleTree;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Productor de {@link AssetAnchorStatusPort} (ficha R-6, G-3 y Enmienda 1, E1; plan del Bloque C, versión 2, §2.4).
 * Solo lectura. Compone {@code core} ({@link StreamEventAnchorDataPort}: una consulta por stream) y {@code crypto}
 * ({@link MerkleBatchRepositoryPort}: una lectura por lote distinto), como {@link TrackingIntegrityService}.
 * <p>
 * Con el lote {@code ANCHORED} y sus {@code leafHashes}, el {@code leafIndex} se <b>calcula</b> desde {@code coverage}
 * con el criterio de {@code IntegrityVerificationUseCase} (streams por {@code streamId} y, en cada uno, por
 * {@code sequence}) y se compara {@code leafHashes[leafIndex]} con el {@code eventHash} actual. <b>Nunca se busca el hash
 * en la lista</b> (plan de ejecución, §0.4): un evento alterado con el hash de otra hoja daría una prueba válida de un
 * hecho que no es el mostrado. Si no coinciden, o el evento no cuadra con el lote, {@code UNDER_REVIEW} sin prueba.
 */
@Component
public class AssetAnchorStatusProducer implements AssetAnchorStatusPort {

    private final StreamEventAnchorDataPort streamEvents;
    private final MerkleBatchRepositoryPort batches;

    public AssetAnchorStatusProducer(StreamEventAnchorDataPort streamEvents, MerkleBatchRepositoryPort batches) {
        this.streamEvents = streamEvents;
        this.batches = batches;
    }

    @Override
    public Map<Long, StepAnchor> anchorBySequence(String streamId) {
        // Un lote, una vez: los pasos del mismo lote usan la misma lista de hojas (§2.4, paso 4.3)
        Map<String, Optional<MerkleBatch>> batchById = new HashMap<>();
        Map<Long, StepAnchor> result = new LinkedHashMap<>();
        for (StreamEventAnchorData event : streamEvents.findByStream(streamId)) {
            Optional<MerkleBatch> batch = event.merkleBatchId() == null
                    ? Optional.empty()
                    : batchById.computeIfAbsent(event.merkleBatchId(), batches::findByBatchId);
            result.put(event.sequence(), stepOf(streamId, event, batch));
        }
        return result;
    }

    private static StepAnchor stepOf(String streamId, StreamEventAnchorData event, Optional<MerkleBatch> found) {
        // Sin lote, o con un lote que aún no se guardó (BlockchainAnchorProducer reclama antes de guardar; hallazgo
        // H-C-2): nada que se pueda comprobar, como P-R6C-3. Nunca se inventa ANCHORED
        if (found.isEmpty()) {
            return new StepAnchor(TrackingAnchorStatus.PENDING, event.eventHash(), null);
        }
        MerkleBatch batch = found.get();
        TrackingAnchorStatus status = trackingStatusOf(batch.status());
        // P-C-3: solo con ANCHORED hay prueba que proteger. Un lote antiguo sin hojas: ANCHORED sin prueba (E1.2)
        if (status != TrackingAnchorStatus.ANCHORED || batch.leafHashes() == null || batch.leafHashes().isEmpty()) {
            return new StepAnchor(status, event.eventHash(), null);
        }
        List<String> leaves = batch.leafHashes();
        Optional<Integer> leafIndex = leafIndexOf(batch.coverage(), streamId, event.sequence(), leaves.size());
        if (leafIndex.isEmpty() || !leaves.get(leafIndex.get()).equals(event.eventHash())) {
            return new StepAnchor(TrackingAnchorStatus.UNDER_REVIEW, event.eventHash(), null);
        }
        List<ProofStep> path = MerkleTree.proof(leaves, leafIndex.get()).stream()
                .map(s -> new ProofStep(s.side() == MerkleTree.Side.LEFT ? Side.LEFT : Side.RIGHT, s.hash()))
                .toList();
        return new StepAnchor(TrackingAnchorStatus.ANCHORED, event.eventHash(), new AnchorProof(batch.merkleRoot(),
                leafIndex.get(), path, batch.transactionHash(), batch.network(), batch.anchoredAt()));
    }

    /**
     * Posición de {@code (streamId, sequence)} en las hojas, con el orden de {@code IntegrityVerificationUseCase}
     * ({@code comparingByKey} y {@code rangeClosed}): los tamaños de los streams de {@code coverage} con clave menor, más
     * {@code sequence − fromSequence}. Vacío si el stream no está en {@code coverage}, la {@code sequence} cae fuera de su
     * rango o el índice no cabe en las hojas (una incoherencia entre el evento y el lote).
     */
    static Optional<Integer> leafIndexOf(Map<String, SequenceRange> coverage, String streamId, long sequence,
                                         int leafCount) {
        SequenceRange own = coverage == null ? null : coverage.get(streamId);
        if (own == null || sequence < own.fromSequence() || sequence > own.toSequence()) {
            return Optional.empty();
        }
        long index = sequence - own.fromSequence();
        for (Map.Entry<String, SequenceRange> e : coverage.entrySet()) {
            if (e.getKey().compareTo(streamId) < 0) {
                index += e.getValue().toSequence() - e.getValue().fromSequence() + 1;
            }
        }
        return index < leafCount ? Optional.of((int) index) : Optional.empty();
    }

    /** Ficha R-6, §5, G-3 (C-4). Sin rama {@code default}: un estado nuevo de {@link AnchorStatus} no compila (9c). */
    static TrackingAnchorStatus trackingStatusOf(AnchorStatus status) {
        return switch (status) {
            case ANCHORED -> TrackingAnchorStatus.ANCHORED;
            case COLLECTING, PENDING, SUBMITTING, SUBMITTED, STUCK -> TrackingAnchorStatus.IN_BATCH;
            case RELEASED -> TrackingAnchorStatus.PENDING;
            case FAILED, COLLECTING_FAILED, ANCHOR_MISMATCH -> TrackingAnchorStatus.UNDER_REVIEW;
        };
    }
}
