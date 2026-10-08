package com.traceability.app.application.anchoring;

import org.springframework.stereotype.Service;

/** Salida manual de un batch COLLECTING_FAILED (Enmienda 1 de ADR-039 §2.3). Esqueleto. */
@Service
public class CollectingFailedBatchService {

    public void retry(String batchId) {
        throw new UnsupportedOperationException("pendiente");
    }

    public void release(String batchId, String operator, String reason) {
        throw new UnsupportedOperationException("pendiente");
    }
}
