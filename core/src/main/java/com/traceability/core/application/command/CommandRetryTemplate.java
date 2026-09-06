package com.traceability.core.application.command;

import com.traceability.core.application.exception.ConcurrencyConflictException;
import com.traceability.core.domain.shared.exceptions.RedundantDomainActionException;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

@Component
public class CommandRetryTemplate {

    private static final int MAX_RETRIES = 3;

    public <T> T execute(Supplier<T> commandAction) {
        int attempts = 0;
        while (attempts < MAX_RETRIES) {
            try {
                return commandAction.get();
            } catch (com.traceability.core.domain.shared.exceptions.DomainInvariantViolationException e) {
                if (e instanceof RedundantDomainActionException) {
                    // Idempotent success (intercepted AFTER invoking the aggregate)
                    return null; // Or some context-aware successful result
                }
                throw e;
            } catch (ConcurrencyConflictException e) {
                attempts++;
                if (attempts >= MAX_RETRIES) {
                    throw new ConcurrencyRetryExhaustedException("Max retries exceeded due to concurrent modifications");
                }
                // Backoff mínimo
                try {
                    Thread.sleep((long) (Math.random() * 40 + 10)); // Jitter aleatorio 10ms - 50ms
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Thread interrupted during retry backoff", ie);
                }
            }
        }
        throw new ConcurrencyRetryExhaustedException("Max retries exceeded due to concurrent modifications");
    }
}
