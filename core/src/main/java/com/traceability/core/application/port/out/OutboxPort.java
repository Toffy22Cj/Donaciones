package com.traceability.core.application.port.out;

import com.traceability.core.application.saga.OutboxMessage;
import com.traceability.core.application.saga.OutboxStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OutboxPort {
    void save(OutboxMessage message);
    
    List<OutboxMessage> fetchPendingMessages(Instant now);
    
    void update(OutboxMessage message);

    Optional<OutboxMessage> findById(String messageId);

    /** El mensaje de una saga por su {@code correlationId} (en la división, el {@code childAssetId}). */
    Optional<OutboxMessage> findBySagaTypeAndCorrelationId(String sagaType, String correlationId);

    /** {@code sagaType} {@code null} = todos. */
    List<OutboxMessage> findQuarantined(String sagaType, int limit);

    long countQuarantined();

    /**
     * Escritura condicionada al estado (ADR-007/008 Enmienda 1, D5).
     *
     * @return {@code false} si el mensaje ya no estaba en {@code expected}; entonces no cambia nada
     */
    boolean updateIfStatus(OutboxMessage message, OutboxStatus expected);
}
