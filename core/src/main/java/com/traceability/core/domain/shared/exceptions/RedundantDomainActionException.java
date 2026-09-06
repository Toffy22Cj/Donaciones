package com.traceability.core.domain.shared.exceptions;

/**
 * Marker interface for domain exceptions that indicate an action is redundant 
 * because the desired state has already been achieved by a previous execution.
 * 
 * Command Handlers should catch exceptions implementing this interface and 
 * treat the operation as an idempotent success rather than a failure.
 */
public interface RedundantDomainActionException {
}
