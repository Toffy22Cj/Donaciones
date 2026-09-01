package com.traceability.core.domain.event;

/**
 * Represents the type of a domain event.
 * 
 * Ref: ADR-013 (Identidad inmutable y separación de payload vs sobre)
 */
public interface EventType {
    String name();
}
