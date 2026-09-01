package com.traceability.core.application.service;

import com.traceability.core.application.port.out.EventStorePort;
import com.traceability.core.application.port.out.OutboxPort;
import com.traceability.core.application.saga.OutboxMessage;
import com.traceability.core.domain.event.DomainEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class TransactionalEventPublisher {

    private final EventStorePort eventStorePort;
    private final OutboxPort outboxPort;

    public TransactionalEventPublisher(EventStorePort eventStorePort, OutboxPort outboxPort) {
        this.eventStorePort = eventStorePort;
        this.outboxPort = outboxPort;
    }

    @Transactional
    public void appendAndOutbox(String streamId, String aggregateType, long expectedVersion, DomainEvent event, String actorRef, List<OutboxMessage> outboxMessages) {
        eventStorePort.append(streamId, aggregateType, expectedVersion, event, actorRef);
        
        if (outboxMessages != null) {
            for (OutboxMessage msg : outboxMessages) {
                outboxPort.save(msg);
            }
        }
    }
}
