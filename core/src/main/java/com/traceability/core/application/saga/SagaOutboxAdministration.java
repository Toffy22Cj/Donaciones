package com.traceability.core.application.saga;

import com.traceability.core.application.port.out.OutboxPort;
import org.springframework.jmx.export.annotation.ManagedAttribute;
import org.springframework.jmx.export.annotation.ManagedOperation;
import org.springframework.jmx.export.annotation.ManagedOperationParameter;
import org.springframework.jmx.export.annotation.ManagedResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Operación manual de la cuarentena de sagas por JMX (ADR-007/008 Enmienda 1, D5; ADR-022; regla 2.6: ninguna
 * cuarentena sin salida). Nunca automática.
 */
@Component
@ManagedResource(objectName = "com.traceability.core:type=SagaOutboxAdministration",
        description = "Quarantined saga messages: list, retry the resolution or mark as resolved by hand")
public class SagaOutboxAdministration {

    private final OutboxPort outboxPort;
    private final SagaManualResolutionService resolutions;

    public SagaOutboxAdministration(OutboxPort outboxPort, SagaManualResolutionService resolutions) {
        this.outboxPort = outboxPort;
        this.resolutions = resolutions;
    }

    @ManagedAttribute(description = "Saga messages that need an operator")
    public long getQuarantinedCount() {
        return outboxPort.countQuarantined();
    }

    @ManagedOperation(description = "Lists quarantined saga messages: messageId | sagaType | correlationId | resolutionStartedAt | lastFailureReason")
    @ManagedOperationParameter(name = "sagaType", description = "Saga type, or empty for all")
    @ManagedOperationParameter(name = "limit", description = "Maximum number of messages")
    public List<String> listQuarantined(String sagaType, int limit) {
        return outboxPort.findQuarantined(sagaType, limit).stream()
                .map(m -> String.join(" | ", m.messageId(), m.sagaType(), m.correlationId(),
                        String.valueOf(m.resolutionStartedAt()), String.valueOf(m.lastFailureReason())))
                .toList();
    }

    @ManagedOperation(description = "Resolves a quarantined saga message again, with a new resolution window")
    @ManagedOperationParameter(name = "messageId", description = "The quarantined saga message")
    @ManagedOperationParameter(name = "operator", description = "Who retries it (audited)")
    @ManagedOperationParameter(name = "note", description = "Why it can be retried (audited)")
    public void retryResolution(String messageId, String operator, String note) {
        requireText(operator, "operator");
        requireText(note, "note");
        resolutions.retryResolution(messageId, operator, note);
    }

    @ManagedOperation(description = "Marks a quarantined saga message as resolved by hand (audited)")
    @ManagedOperationParameter(name = "messageId", description = "The quarantined saga message")
    @ManagedOperationParameter(name = "operator", description = "Who resolved it (audited)")
    @ManagedOperationParameter(name = "note", description = "What was done; for a split, what happened to the quantity (audited)")
    public void markResolvedManually(String messageId, String operator, String note) {
        requireText(operator, "operator");
        requireText(note, "note");
        resolutions.markResolvedManually(messageId, operator, note);
    }

    private static void requireText(String value, String name) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
