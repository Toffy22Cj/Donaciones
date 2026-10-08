package com.traceability.app.application.anchoring;

import org.springframework.jmx.export.annotation.ManagedOperation;
import org.springframework.jmx.export.annotation.ManagedOperationParameter;
import org.springframework.jmx.export.annotation.ManagedResource;
import org.springframework.stereotype.Component;

/**
 * Operación JMX para un batch {@code COLLECTING_FAILED} (Enmienda 1 de ADR-039 §2.3): mismo patrón que
 * {@code BlockchainAdminOperations.resolveStuckBatch} (ADR-022). Nunca automática.
 */
@Component
@ManagedResource(objectName = "com.traceability.app.application.anchoring:type=CollectingFailedBatchAdminOperations",
        description = "Salida manual de batches COLLECTING_FAILED")
public class CollectingFailedBatchAdminOperations {

    private final CollectingFailedBatchService service;

    public CollectingFailedBatchAdminOperations(CollectingFailedBatchService service) {
        this.service = service;
    }

    @ManagedOperation(description = "RETRY: vuelve a COLLECTING con recoveryAttempts = 0; RELEASE: RELEASED, libera sus "
            + "eventos (solo los de este batch dentro de su cobertura) y deja la auditoría")
    @ManagedOperationParameter(name = "batchId", description = "Batch en COLLECTING_FAILED")
    @ManagedOperationParameter(name = "resolution", description = "RETRY o RELEASE")
    @ManagedOperationParameter(name = "operator", description = "Quién lo hace (auditoría de RELEASE)")
    @ManagedOperationParameter(name = "reason", description = "Por qué (auditoría de RELEASE)")
    public void resolveCollectingFailedBatch(String batchId, String resolution, String operator, String reason) {
        switch (resolution == null ? "" : resolution.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "RETRY" -> service.retry(batchId);
            case "RELEASE" -> service.release(batchId, operator, reason);
            default -> throw new IllegalArgumentException("resolution must be RETRY or RELEASE");
        }
    }
}
