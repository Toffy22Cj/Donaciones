package com.traceability.core.domain.shared;

import com.traceability.core.domain.event.DomainEventPayload;
import com.traceability.core.domain.event.EventType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AggregateRootTest {

    static class TestPayload implements DomainEventPayload {
        final String data;
        TestPayload(String data) { this.data = data; }
    }

    enum TestEventType implements EventType {
        TEST_EVENT
    }

    static class TestAggregate extends AggregateRoot {
        String lastData;
        int applyCount = 0;

        @Override
        protected void apply(DomainEventPayload payload) {
            if (payload instanceof TestPayload tp) {
                this.lastData = tp.data;
                this.applyCount++;
            }
        }

        public void doSomething(String data) {
            raiseEvent(TestEventType.TEST_EVENT, new TestPayload(data));
        }
    }

    @Test
    void testRaiseEvent_AppendsToUncommittedAndApplies() {
        TestAggregate aggregate = new TestAggregate();
        aggregate.doSomething("hello");

        assertEquals(1, aggregate.applyCount);
        assertEquals("hello", aggregate.lastData);
        assertEquals(1, aggregate.version);
        
        assertEquals(1, aggregate.getUncommittedEvents().size());
        assertEquals(TestEventType.TEST_EVENT, aggregate.getUncommittedEvents().get(0).eventType());
    }

    @Test
    void testReplay_RehydratesStateWithoutAppendingToUncommitted() {
        TestAggregate aggregate = new TestAggregate();
        
        DomainEventPayload payload1 = new TestPayload("hist1");
        DomainEventPayload payload2 = new TestPayload("hist2");

        aggregate.replay(List.of(payload1, payload2), 2);

        assertEquals(2, aggregate.applyCount);
        assertEquals("hist2", aggregate.lastData);
        assertEquals(2, aggregate.version);
        
        assertTrue(aggregate.getUncommittedEvents().isEmpty());
    }
}
