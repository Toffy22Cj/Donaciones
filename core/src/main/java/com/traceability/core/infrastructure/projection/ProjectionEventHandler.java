package com.traceability.core.infrastructure.projection;

import com.traceability.core.infrastructure.persistence.mongo.TraceabilityEventDocument;

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
    void handleEvent(TraceabilityEventDocument eventDoc);

    /**
     * Returns the unique name of this handler.
     * Used for routing retries from the ProjectionRetryScheduler.
     * @return the handler name
     */
    String getHandlerName();
}
