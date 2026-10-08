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

    /**
     * @return {@code true} si escribió los eventos; {@code false} si el {@code commandId} ya estaba reclamado
     *         (no-op idempotente, no se escribe nada).
     */
    @Transactional
    public boolean appendAndOutbox(String streamId, String aggregateType, long expectedVersion, List<DomainEvent> events, com.traceability.core.domain.event.ActorRef actorRef, List<OutboxMessage> outboxMessages, String commandId) {
        return appendAndOutbox(streamId, aggregateType, expectedVersion, events, actorRef, outboxMessages, commandId, null);
    }

    /**
     * Igual que {@link #appendAndOutbox(String, String, long, List, com.traceability.core.domain.event.ActorRef, List, String)},
     * guardando en el reclamo el resultado que lo ganó ({@code claimOutcome}). Es la barrera de la división (plan B1-bis
     * §2): el reclamo y su efecto se confirman juntos o no se confirma ninguno.
     */
    @Transactional
    public boolean appendAndOutbox(String streamId, String aggregateType, long expectedVersion, List<DomainEvent> events, com.traceability.core.domain.event.ActorRef actorRef, List<OutboxMessage> outboxMessages, String commandId, String claimOutcome) {
        if (commandId != null) {
            boolean claimed = claimOutcome == null
                    ? processedCommandRepositoryPort.tryClaim(commandId)
                    : processedCommandRepositoryPort.tryClaim(commandId, claimOutcome);
            if (!claimed) {
                return false;
            }
        }
        
        eventStorePort.append(streamId, aggregateType, expectedVersion, events, actorRef);
        
        if (outboxMessages != null) {
            for (OutboxMessage msg : outboxMessages) {
                outboxPort.save(msg);
            }
        }
        return true;
    }
}
