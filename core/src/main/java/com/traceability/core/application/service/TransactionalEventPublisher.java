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
    private final com.traceability.core.application.port.out.ProcessedCommandRepositoryPort processedCommandRepositoryPort;

    public TransactionalEventPublisher(EventStorePort eventStorePort, OutboxPort outboxPort, com.traceability.core.application.port.out.ProcessedCommandRepositoryPort processedCommandRepositoryPort) {
        this.eventStorePort = eventStorePort;
        this.outboxPort = outboxPort;
        this.processedCommandRepositoryPort = processedCommandRepositoryPort;
    }

    @Transactional
    public void appendAndOutbox(String streamId, String aggregateType, long expectedVersion, List<DomainEvent> events, String actorRef, List<OutboxMessage> outboxMessages, String commandId) {
        if (commandId != null) {
            processedCommandRepositoryPort.save(commandId);
        }
        
        eventStorePort.append(streamId, aggregateType, expectedVersion, events, actorRef);
        
        if (outboxMessages != null) {
            for (OutboxMessage msg : outboxMessages) {
                outboxPort.save(msg);
            }
        }
    }
}
