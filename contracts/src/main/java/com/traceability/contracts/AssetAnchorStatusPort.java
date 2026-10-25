package com.traceability.contracts;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Estado de anclaje, hash y, si el lote está {@code ANCHORED}, prueba de Merkle de cada evento de un stream (ficha R-6,
 * G-3 y Enmienda 1, E1; plan del Bloque C, versión 2, §2.3 (1), P-R6C-1). Solo lectura. Declarado en contracts para
 * que lo use api, que no ve crypto; implementado en app.
 */
public interface AssetAnchorStatusPort {

    /** Por {@code sequence} del Event Store. Un evento sin lote es {@link TrackingAnchorStatus#PENDING}. */
    Map<Long, StepAnchor> anchorBySequence(String streamId);

    /** {@code proof} es nulo si no hay prueba (cualquier estado que no sea {@code ANCHORED} con hojas guardadas). */
    record StepAnchor(TrackingAnchorStatus status, String eventHash, AnchorProof proof) {}

    /** El {@code anchorProof} de E1.2, sin {@code leafCount} (plan de ejecución, §0.4). */
    record AnchorProof(String merkleRoot, int leafIndex, List<ProofStep> path,
                       String transactionHash, String network, Instant anchoredAt) {}

    /** Un paso de la prueba, de la hoja hacia la raíz (E1.3). */
    record ProofStep(Side side, String hash) {}

    enum Side { LEFT, RIGHT }
}
