package com.traceability.core.application.port.out;

import java.util.List;

/**
 * Hash y lote de cada evento de un stream, en orden de {@code sequence} (ficha R-6, Enmienda 1, E1; plan del Bloque C,
 * versión 2, §2.3 (2) y P-C-2). Solo lectura. Es un puerto nuevo porque {@link EventBatchMembershipPort} no da el hash
 * y no se cambia (regla 3.5): lo sigue usando la integridad del seguimiento.
 */
public interface StreamEventAnchorDataPort {

    /** {@code merkleBatchId} es nulo si el evento aún no está en ningún lote. */
    record StreamEventAnchorData(long sequence, String eventHash, String merkleBatchId) {}

    /** Vacío si el stream no tiene eventos. */
    List<StreamEventAnchorData> findByStream(String streamId);
}
