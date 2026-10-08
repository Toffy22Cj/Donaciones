package com.traceability.core.application.query;

import com.traceability.core.application.port.out.EventStorePort;
import com.traceability.core.application.port.out.OutboxPort;
import com.traceability.core.application.port.out.ProcessedCommandRepositoryPort;
import com.traceability.core.application.port.out.SplitResolutionReadPort;
import com.traceability.core.application.saga.OutboxStatus;
import com.traceability.core.application.saga.SplitPhysicalAssetSagaPolicy;
import com.traceability.core.application.saga.SplitResolution;
import com.traceability.core.application.saga.SplitResolutionStatus;
import com.traceability.core.domain.event.DomainEvent;
import com.traceability.core.domain.physicalasset.PhysicalAsset;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Estado de una división (D-SPLIT S7; plan B1-bis Q3). El resultado del reclamo {@code SPLIT_RESOLUTION:{childAssetId}}
 * manda; sin reclamo, la división está {@code PENDING} o, si su mensaje está en cuarentena, {@code UNRESOLVED}.
 */
@Service
public class SplitResolutionQueryService implements SplitResolutionReadPort {

    private final EventStorePort eventStore;
    private final ProcessedCommandRepositoryPort processedCommands;
    private final OutboxPort outboxPort;

    public SplitResolutionQueryService(EventStorePort eventStore, ProcessedCommandRepositoryPort processedCommands,
                                       OutboxPort outboxPort) {
        this.eventStore = eventStore;
        this.processedCommands = processedCommands;
        this.outboxPort = outboxPort;
    }

    @Override
    public Optional<SplitResolutionStatus> findStatus(String parentAssetId, String childAssetId) {
        List<DomainEvent> events = eventStore.loadStream(parentAssetId);
        if (events.isEmpty()) {
            return Optional.empty();
        }
        PhysicalAsset parent = PhysicalAsset.rehydrate(parentAssetId, events.stream().map(DomainEvent::payload).toList(),
                events.size());
        if (parent.findSplit(childAssetId).isEmpty()) {
            return Optional.empty();
        }
        Optional<String> outcome = processedCommands.findOutcome(SplitResolution.claimKey(childAssetId));
        if (outcome.isPresent()) {
            return Optional.of(SplitResolutionStatus.valueOf(outcome.get()));
        }
        boolean quarantined = outboxPort.findBySagaTypeAndCorrelationId(SplitPhysicalAssetSagaPolicy.SAGA_TYPE, childAssetId)
                .map(m -> m.status() == OutboxStatus.QUARANTINED)
                .orElse(false);
        return Optional.of(quarantined ? SplitResolutionStatus.UNRESOLVED : SplitResolutionStatus.PENDING);
    }
}
