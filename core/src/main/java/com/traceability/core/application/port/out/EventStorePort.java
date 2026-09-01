package com.traceability.core.application.port.out;

import com.traceability.core.domain.event.DomainEvent;

import java.util.List;

public interface EventStorePort {
    /**
     * Appends a new event to the stream, ensuring exact sequence matching for concurrency control.
     */
    void append(String streamId, String aggregateType, long expectedVersion, DomainEvent event, String actorRef);
    
    /**
     * Loads the entire event stream sorted by sequence ascending.
     */
    List<DomainEvent> loadStream(String streamId);
}
