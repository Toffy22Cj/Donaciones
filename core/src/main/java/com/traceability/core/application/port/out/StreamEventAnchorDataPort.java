package com.traceability.core.application.port.out;

import java.util.List;

/**
 * Hash y lote de cada evento de un stream, en orden de {@code sequence} (ficha R-6, Enmienda 1, E1; plan del Bloque C,
 * versión 2, §2.3 (2) y P-C-2). Solo lectura. Es un puerto nuevo porque {@link EventBatchMembershipPort} no da el hash
 * y no se cambia (regla 3.5): lo sigue usando la integridad del seguimiento.
 */
public interface StreamEventAnchorDataPort {

    /**
     * {@code merkleBatchId} es nulo si el evento aún no está en ningún lote. {@code evidenceRef}: el del payload, tal
     * cual (huella {@code sha256:} o texto libre heredado), solo de un {@code ASSET_DELIVERED}; nulo en los demás (ADR-051,
     * Tarea 5; plan de cierre, P-T5-1). Qué se expone de él lo decide la enmienda de ADR-021-D, no este puerto.
     */
    record StreamEventAnchorData(long sequence, String eventHash, String merkleBatchId, String evidenceRef) {

        /** Sin {@code evidenceRef}, como antes de la Tarea 5. */
        public StreamEventAnchorData(long sequence, String eventHash, String merkleBatchId) {
            this(sequence, eventHash, merkleBatchId, null);
        }
    }

    /** Vacío si el stream no tiene eventos. */
    List<StreamEventAnchorData> findByStream(String streamId);
}
