package com.traceability.core.application.saga;

public interface SagaPolicy {
    
    /**
     * @return Identifies the type of saga this policy handles.
     */
    String getSagaType();
    
    /**
     * Attempts the main operation (e.g., create Asset). Throws exception if it fails.
     */
    void execute(OutboxMessage message);
    
    /**
     * Executes compensation on the source aggregate (e.g., ALLOCATION_REVERSED).
     * Must be resilient to failure.
     */
    void compensate(OutboxMessage message);
}
