package com.traceability.core.application.port.out;

import com.traceability.core.application.saga.OutboxMessage;

import java.time.Instant;
import java.util.List;

public interface OutboxPort {
    void save(OutboxMessage message);
    
    List<OutboxMessage> fetchPendingMessages(Instant now);
    
    void update(OutboxMessage message);
}
