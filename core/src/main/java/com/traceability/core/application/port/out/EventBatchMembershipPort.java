package com.traceability.core.application.port.out;

import java.util.Collection;
import java.util.List;

/**
 * En qué lote de Merkle está cada evento de unos streams (campo {@code merkleBatchId} del evento; {@code null} si aún
 * no está en ninguno). Lo usa la verificación de integridad del seguimiento (encargo 6, P4). Solo lectura.
 */
public interface EventBatchMembershipPort {

    record EventMembership(String streamId, long sequence, String merkleBatchId) {}

    /** Como mucho {@code limit} eventos de esos streams. */
    List<EventMembership> findMembership(Collection<String> streamIds, int limit);
}
