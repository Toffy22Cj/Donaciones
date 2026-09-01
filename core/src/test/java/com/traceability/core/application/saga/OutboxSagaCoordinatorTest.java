package com.traceability.core.application.saga;

import com.traceability.core.application.port.out.OutboxPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OutboxSagaCoordinatorTest {

    private MockOutboxPort outboxPort;
    private MockSagaPolicy testPolicy;
    private OutboxSagaCoordinator coordinator;
    
    private final Duration quarantineWindow = Duration.ofHours(4);

    @BeforeEach
    void setUp() {
        outboxPort = new MockOutboxPort();
        testPolicy = new MockSagaPolicy("TEST_SAGA");
        coordinator = new OutboxSagaCoordinator(outboxPort, List.of(testPolicy), quarantineWindow);
    }

    @Test
    void testProcessPendingMessages_Success_MarksCompleted() {
        OutboxMessage msg = new OutboxMessage(
            "msg-1", "TEST_SAGA", "agg-1", "corr-1", "{}", 
            OutboxStatus.PENDING, 0, Instant.now().minus(Duration.ofMinutes(10)), Instant.now()
        );
        outboxPort.save(msg);

        coordinator.processPendingMessages();

        assertTrue(testPolicy.executed);
        assertFalse(testPolicy.compensated);
        
        OutboxMessage updated = outboxPort.getUpdates().get("msg-1");
        assertNotNull(updated);
        assertEquals(OutboxStatus.COMPLETED, updated.status());
    }

    @Test
    void testProcessPendingMessages_ExecuteFails_ExponentialBackoff() {
        OutboxMessage msg = new OutboxMessage(
            "msg-2", "TEST_SAGA", "agg-1", "corr-1", "{}", 
            OutboxStatus.PENDING, 2, Instant.now().minus(Duration.ofMinutes(10)), Instant.now()
        );
        outboxPort.save(msg);
        testPolicy.shouldFailExecute = true;

        Instant beforeProcess = Instant.now();
        coordinator.processPendingMessages();

        assertTrue(testPolicy.executed);
        
        OutboxMessage updated = outboxPort.getUpdates().get("msg-2");
        assertNotNull(updated);
        assertEquals(OutboxStatus.PENDING, updated.status()); // Still PENDING
        assertEquals(3, updated.retryCount()); // retryCount increased from 2 to 3
        
        // 2^2 * 15 = 4 * 15 = 60 seconds
        assertTrue(updated.nextRetryAt().isAfter(beforeProcess.plusSeconds(59)));
        assertTrue(updated.nextRetryAt().isBefore(beforeProcess.plusSeconds(65)));
    }

    @Test
    void testProcessPendingMessages_Quarantine_CompensatesAndQuarantines() {
        OutboxMessage msg = new OutboxMessage(
            "msg-3", "TEST_SAGA", "agg-1", "corr-1", "{}", 
            OutboxStatus.PENDING, 10, Instant.now().minus(Duration.ofHours(5)), Instant.now()
        );
        outboxPort.save(msg);

        coordinator.processPendingMessages();

        assertFalse(testPolicy.executed); // Should not even try to execute
        assertTrue(testPolicy.compensated); // Should compensate immediately
        
        OutboxMessage updated = outboxPort.getUpdates().get("msg-3");
        assertNotNull(updated);
        assertEquals(OutboxStatus.QUARANTINED, updated.status());
        assertEquals(10, updated.retryCount()); // Retry count should not increase
    }

    @Test
    void testProcessPendingMessages_QuarantineFails_StillQuarantinesAndContinues() {
        OutboxMessage msg1 = new OutboxMessage(
            "msg-4", "TEST_SAGA", "agg-1", "corr-1", "{}", 
            OutboxStatus.PENDING, 10, Instant.now().minus(Duration.ofHours(5)), Instant.now()
        );
        OutboxMessage msg2 = new OutboxMessage(
            "msg-5", "TEST_SAGA", "agg-2", "corr-2", "{}", 
            OutboxStatus.PENDING, 0, Instant.now().minus(Duration.ofMinutes(5)), Instant.now()
        );
        outboxPort.save(msg1);
        outboxPort.save(msg2);
        
        testPolicy.shouldFailCompensate = true; // This will fail during msg-4

        coordinator.processPendingMessages();

        // msg-4 should be QUARANTINED even if compensate failed
        OutboxMessage updated1 = outboxPort.getUpdates().get("msg-4");
        assertNotNull(updated1);
        assertEquals(OutboxStatus.QUARANTINED, updated1.status());
        
        // msg-5 should have been processed successfully!
        OutboxMessage updated2 = outboxPort.getUpdates().get("msg-5");
        assertNotNull(updated2);
        assertEquals(OutboxStatus.COMPLETED, updated2.status());
    }

    // --- Mocks ---

    static class MockOutboxPort implements OutboxPort {
        private final List<OutboxMessage> pending = new ArrayList<>();
        private final Map<String, OutboxMessage> updates = new HashMap<>();

        @Override
        public void save(OutboxMessage message) {
            pending.add(message);
        }

        @Override
        public List<OutboxMessage> fetchPendingMessages(Instant now) {
            return pending;
        }

        @Override
        public void update(OutboxMessage message) {
            updates.put(message.messageId(), message);
        }
        
        public Map<String, OutboxMessage> getUpdates() {
            return updates;
        }
    }

    static class MockSagaPolicy implements SagaPolicy {
        private final String sagaType;
        boolean executed = false;
        boolean compensated = false;
        boolean shouldFailExecute = false;
        boolean shouldFailCompensate = false;

        MockSagaPolicy(String sagaType) {
            this.sagaType = sagaType;
        }

        @Override
        public String getSagaType() {
            return sagaType;
        }

        @Override
        public void execute(OutboxMessage message) {
            executed = true;
            if (shouldFailExecute) throw new RuntimeException("Execute failed");
        }

        @Override
        public void compensate(OutboxMessage message) {
            compensated = true;
            if (shouldFailCompensate) throw new RuntimeException("Compensate failed");
        }
    }
}
