package com.traceability.core.infrastructure.projection;

import com.traceability.core.domain.event.DomainEventPayload;
import com.traceability.core.infrastructure.persistence.mongo.TraceabilityEventDocument;

import java.util.Set;

/**
 * Interface for all projection handlers.
 * Allows decoupling ProjectionEventSource from specific handlers, enabling multiple
 * independent projection updates from the same change stream.
 */
public interface ProjectionEventHandler {
    
    /**
     * Handles an incoming event document.
     * Implementations must handle their own idempotency and persistence logic.
     * @param eventDoc the raw event document from the event store
     */
    void handleEvent(TraceabilityEventDocument eventDoc) throws Exception;

    /**
     * Returns the unique name of this handler.
     * Used for routing retries from the ProjectionRetryScheduler.
     * @return the handler name
     */
    String getHandlerName();

    /**
     * Payloads que este manejador trata (B-PROJ). Junto con {@link #ignoredPayloads()} debe cubrir cada clase de
     * {@code EventPayloadRegistry}; lo comprueba {@code ProjectionPayloadContractTest}.
     */
    default Set<Class<? extends DomainEventPayload>> handledPayloads() {
        return Set.of();
    }

    /**
     * Payloads que este manejador ignora de forma explícita: en los manejadores con secuencia, avanzan la secuencia
     * sin cambiar datos.
     */
    default Set<Class<? extends DomainEventPayload>> ignoredPayloads() {
        return Set.of();
    }
}
