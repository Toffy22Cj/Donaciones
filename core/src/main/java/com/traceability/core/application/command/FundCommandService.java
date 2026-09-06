package com.traceability.core.application.command;

import com.traceability.core.application.port.out.EventStorePort;
import com.traceability.core.application.port.out.ProcessedCommandRepositoryPort;
import com.traceability.core.application.service.TransactionalEventPublisher;
import com.traceability.core.domain.event.DomainEvent;
import com.traceability.core.domain.event.DomainEventPayload;
import com.traceability.core.domain.fund.Fund;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class FundCommandService {

    private final CommandRetryTemplate retryTemplate;
    private final ProcessedCommandRepositoryPort processedCommandRepository;
    private final EventStorePort eventStore;
    private final TransactionalEventPublisher eventPublisher;

    public FundCommandService(CommandRetryTemplate retryTemplate,
                              ProcessedCommandRepositoryPort processedCommandRepository,
                              EventStorePort eventStore,
                              TransactionalEventPublisher eventPublisher) {
        this.retryTemplate = retryTemplate;
        this.processedCommandRepository = processedCommandRepository;
        this.eventStore = eventStore;
        this.eventPublisher = eventPublisher;
    }

    public void confirmAllocation(String commandId, String fundId, String allocationId) {
        if (processedCommandRepository.exists(commandId)) {
            return;
        }

        retryTemplate.execute(() -> {
            List<DomainEvent> events = eventStore.loadStream(fundId);
            List<DomainEventPayload> payloads = events.stream().map(DomainEvent::payload).collect(Collectors.toList());
            Fund fund = Fund.rehydrate(fundId, payloads, events.size());
            long expectedVersion = fund.getVersion();
            
            fund.confirmAllocation(allocationId);
            
            List<DomainEvent> newEvents = fund.getUncommittedEvents();
            if (!newEvents.isEmpty()) {
                eventPublisher.appendAndOutbox(fundId, "Fund", expectedVersion, newEvents, "SYSTEM", null, commandId);
            }
            return null;
        });
    }

    public void reverseAllocation(String commandId, String fundId, String allocationId, String reason) {
        if (processedCommandRepository.exists(commandId)) {
            return;
        }

        retryTemplate.execute(() -> {
            List<DomainEvent> events = eventStore.loadStream(fundId);
            List<DomainEventPayload> payloads = events.stream().map(DomainEvent::payload).collect(Collectors.toList());
            Fund fund = Fund.rehydrate(fundId, payloads, events.size());
            long expectedVersion = fund.getVersion();
            
            fund.reverseAllocation(allocationId, reason);
            
            List<DomainEvent> newEvents = fund.getUncommittedEvents();
            if (!newEvents.isEmpty()) {
                eventPublisher.appendAndOutbox(fundId, "Fund", expectedVersion, newEvents, "SYSTEM", null, commandId);
            }
            return null;
        });
    }
}
