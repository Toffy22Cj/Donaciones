package com.traceability.core.domain.shared;

import com.traceability.core.domain.event.DomainEvent;
import com.traceability.core.domain.event.DomainEventPayload;
import com.traceability.core.domain.event.EventType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Base class for all Aggregate Roots.
 * Ignores infrastructure details (eventId, persistence, etc.) and focuses on pure business logic.
 * 
 * Ref: ADR-013 (Identidad inmutable y separación de payload vs sobre)
 */
public abstract class AggregateRoot {
    protected String streamId;
    protected long version;
    private final List<DomainEvent> uncommittedEvents = new ArrayList<>();

    /**
     * Rehydrates the aggregate from its past history (pure payloads).
     */
    public void replay(Iterable<DomainEventPayload> historicalPayloads, long currentVersion) {
        for (DomainEventPayload payload : historicalPayloads) {
            this.apply(payload);
        }
        this.version = currentVersion;
    }

    /**
     * Mutates the aggregate's internal state based on the event payload.
     * Must be implemented by concrete aggregates.
     */
    protected abstract void apply(DomainEventPayload payload);

    /**
     * Emits a new domain event and applies it to the aggregate's state.
     */
    protected void raiseEvent(EventType type, DomainEventPayload payload) {
        this.apply(payload);
        this.version++;
        this.uncommittedEvents.add(new DomainEvent(type, payload, Instant.now()));
    }
    
    public List<DomainEvent> getUncommittedEvents() {
        return List.copyOf(uncommittedEvents);
    }
    
    public void clearUncommittedEvents() {
        this.uncommittedEvents.clear();
    }
    
    public long getVersion() {
        return version;
    }
}
