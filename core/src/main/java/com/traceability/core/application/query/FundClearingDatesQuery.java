package com.traceability.core.application.query;

import com.traceability.core.application.port.out.EventStorePort;
import com.traceability.core.application.port.out.FundClearingDatesReadPort;
import com.traceability.core.domain.event.DomainEvent;
import com.traceability.core.domain.fund.payloads.FundsClearedPayload;
import com.traceability.core.domain.fund.payloads.FundsClearedV2Payload;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * {@link FundClearingDatesReadPort} sobre el event store (ficha R-6, G-2): lee el stream del fondo, ya ordenado por
 * {@code sequence}, y toma el {@code occurredAt} del primer y del último {@code FUNDS_CLEARED}, de cualquier versión.
 * Sin autorización propia: el {@code trackingCode} ya resolvió ese fondo, como en el resto del seguimiento.
 */
@Service
public class FundClearingDatesQuery implements FundClearingDatesReadPort {

    private final EventStorePort eventStore;

    public FundClearingDatesQuery(EventStorePort eventStore) {
        this.eventStore = eventStore;
    }

    @Override
    public Optional<ClearingDates> findClearingDates(String fundId) {
        List<DomainEvent> clearings = eventStore.loadStream(fundId).stream()
                .filter(e -> e.payload() instanceof FundsClearedPayload || e.payload() instanceof FundsClearedV2Payload)
                .toList();
        if (clearings.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ClearingDates(clearings.get(0).occurredAt(),
                clearings.get(clearings.size() - 1).occurredAt()));
    }
}
