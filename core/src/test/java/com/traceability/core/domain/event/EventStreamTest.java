package com.traceability.core.domain.event;

import com.traceability.core.domain.shared.exceptions.SequenceConflictException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EventStreamTest {

    @Test
    void testValidSequence_DoesNotThrow() {
        var event1 = createEvent(1);
        var event2 = createEvent(2);
        
        assertDoesNotThrow(() -> new EventStream("stream1", 2, List.of(event1, event2), "hash"));
    }

    @Test
    void testInvalidSequenceGap_ThrowsException() {
        var event1 = createEvent(1);
        var event3 = createEvent(3);
        
        assertThrows(SequenceConflictException.class, () -> 
            new EventStream("stream1", 3, List.of(event1, event3), "hash"));
    }
    
    @Test
    void testInvalidSequenceDuplicate_ThrowsException() {
        var event1 = createEvent(1);
        var event1dup = createEvent(1);
        
        assertThrows(SequenceConflictException.class, () -> 
            new EventStream("stream1", 2, List.of(event1, event1dup), "hash"));
    }

    private TraceabilityEvent createEvent(long sequence) {
        return new TraceabilityEvent("id", "stream1", sequence, "key", "TYPE", "1.0",
                Instant.now(), Instant.now(), "actor", null, "prev", "hash");
    }
}
