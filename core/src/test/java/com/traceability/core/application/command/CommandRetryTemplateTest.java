package com.traceability.core.application.command;

import com.traceability.core.application.exception.ConcurrencyConflictException;
import com.traceability.core.domain.shared.exceptions.DomainInvariantViolationException;
import com.traceability.core.domain.shared.exceptions.RedundantDomainActionException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class CommandRetryTemplateTest {

    private final CommandRetryTemplate retryTemplate = new CommandRetryTemplate();

    @Test
    void testExecute_SuccessOnFirstAttempt() {
        String result = retryTemplate.execute(() -> "SUCCESS");
        assertEquals("SUCCESS", result);
    }

    @Test
    void testExecute_SuccessAfterRetries() {
        AtomicInteger attempts = new AtomicInteger(0);
        
        String result = retryTemplate.execute(() -> {
            int current = attempts.incrementAndGet();
            if (current < 3) {
                throw new ConcurrencyConflictException("Conflict simulated");
            }
            return "SUCCESS";
        });
        
        assertEquals("SUCCESS", result);
        assertEquals(3, attempts.get());
    }

    @Test
    void testExecute_ExhaustsRetries() {
        AtomicInteger attempts = new AtomicInteger(0);
        
        assertThrows(ConcurrencyRetryExhaustedException.class, () -> {
            retryTemplate.execute(() -> {
                attempts.incrementAndGet();
                throw new ConcurrencyConflictException("Conflict simulated");
            });
        });
        
        assertEquals(3, attempts.get()); // MAX_RETRIES = 3
    }

    @Test
    void testExecute_RedundantDomainActionIsTreatedAsSuccess() {
        // Redundant exception must extend DomainInvariantViolationException
        class TestRedundantException extends DomainInvariantViolationException implements RedundantDomainActionException {
            public TestRedundantException() {
                super("Redundant action");
            }
        }
        
        String result = retryTemplate.execute(() -> {
            throw new TestRedundantException();
        });
        
        assertNull(result); // The template should catch it and return null
    }

    @Test
    void testExecute_OtherExceptionsArePropagated() {
        class OtherDomainException extends DomainInvariantViolationException {
            public OtherDomainException() {
                super("Other invariant violated");
            }
        }
        
        assertThrows(OtherDomainException.class, () -> {
            retryTemplate.execute(() -> {
                throw new OtherDomainException();
            });
        });
    }
}
