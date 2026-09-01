package com.traceability.core.domain.event;

import com.traceability.core.domain.shared.exceptions.SequenceConflictException;
import java.util.List;

/**
 * Represents a stream of events for a specific aggregate.
 * Ensures immutability and enforces sequence rules.
 * 
 * Ref: ADR-013 (Validación de secuencia)
 */
public record EventStream(
    String streamId,
    long currentVersion,
    List<TraceabilityEvent> events,
    String lastEventHash
) {
    public EventStream {
        events = events == null ? List.of() : List.copyOf(events);
        
        if (currentVersion < 0) {
            throw new IllegalArgumentException("currentVersion cannot be negative");
        }

        if (!events.isEmpty()) {
            long expectedSeq = events.get(0).sequence();
            for (TraceabilityEvent event : events) {
                if (event.sequence() != expectedSeq) {
                    throw new SequenceConflictException(streamId, expectedSeq, event.sequence());
                }
                expectedSeq++;
            }
        }
    }
}
