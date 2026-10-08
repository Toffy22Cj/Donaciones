package com.traceability.core.application.port.out;

import com.traceability.contracts.SequenceRange;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Eventos tal como están guardados, para recalcular su {@code eventHash} (Enmienda 1 de ADR-039, aprobada por Carlos el
 * 2026-10-08). Se leen en crudo: {@code legacyActorRef} es el {@code actorRef} guardado como texto, la forma anterior a
 * {@code 0579f41}, que el mapeo actual a {@code ActorRef} no lee. Solo lectura.
 */
public interface StoredEventReadPort {

    record StoredEvent(String eventId, String streamId, String aggregateType, long sequence, String eventType,
                       String schemaVersion, String occurredAt, String recordedAt, String origin,
                       Map<String, Object> payload, String previousHash, String eventHash, String legacyActorRef) {}

    /** Los eventos de la cobertura, por {@code streamId} y {@code sequence} ascendentes. */
    List<StoredEvent> findByCoverage(Map<String, SequenceRange> coverage);

    Optional<StoredEvent> find(String streamId, long sequence);
}
