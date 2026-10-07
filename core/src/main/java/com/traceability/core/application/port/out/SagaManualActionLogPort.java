package com.traceability.core.application.port.out;

import java.time.Instant;

/** Registro de auditoría de solo inserción de las acciones manuales sobre sagas (ADR-007/008 Enmienda 1, D5). */
public interface SagaManualActionLogPort {

    void record(String messageId, String sagaType, String correlationId, String action, String previousStatus,
                String operator, String note, Instant at);
}
