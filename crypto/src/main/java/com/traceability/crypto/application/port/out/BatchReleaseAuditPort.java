package com.traceability.crypto.application.port.out;

import com.traceability.contracts.SequenceRange;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Registro de cada RELEASE de un batch COLLECTING_FAILED (Enmienda 1 de ADR-039 §2.3): poner
 * {@code merkleBatchId = null} borra la evidencia de que esos eventos estuvieron en el batch, así que se guarda aquí, en
 * la misma transacción.
 */
public interface BatchReleaseAuditPort {

    record BatchRelease(String batchId, Map<String, SequenceRange> coverage, List<String> eventIds, String operator,
                        String reason, Instant releasedAt) {}

    void record(BatchRelease release);
}
