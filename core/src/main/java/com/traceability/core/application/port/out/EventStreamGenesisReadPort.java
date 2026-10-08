package com.traceability.core.application.port.out;

import com.traceability.core.domain.event.DomainEventPayload;

import java.util.Optional;

/**
 * Lectura estrecha del primer evento de un stream (B-PROJ). Separada de {@link EventStorePort} para no tocar el
 * contrato de escritura del event store.
 */
public interface EventStreamGenesisReadPort {

    /**
     * Secuencia del primer evento de todo stream (D-SEQ, documento maestro, glosario "sequence"): la génesis se escribe
     * con {@code expectedVersion = 0} y recibe la secuencia 1. Única definición del origen de la secuencia.
     */
    long FIRST_SEQUENCE = 1;

    /** Payload tipado del evento génesis del stream, o vacío si el stream no existe todavía. */
    Optional<DomainEventPayload> findGenesisPayload(String streamId);
}
