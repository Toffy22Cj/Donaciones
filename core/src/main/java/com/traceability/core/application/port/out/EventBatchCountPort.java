package com.traceability.core.application.port.out;

import java.util.Collection;
import java.util.List;

/**
 * Cuántos eventos de unos streams hay en cada lote de Merkle (ficha R-6, G-7; plan D, §2.1 y P-R6D-3): una agregación
 * por {@code merkleBatchId}, sin tope (C-5). Solo cuenta los streams pedidos, aunque un lote mezcle organizaciones.
 * Solo lectura.
 */
public interface EventBatchCountPort {

    /** {@code merkleBatchId} nulo: los eventos que aún no están en ningún lote. */
    record BatchEventCount(String merkleBatchId, long events) {}

    List<BatchEventCount> countByBatch(Collection<String> streamIds);
}
