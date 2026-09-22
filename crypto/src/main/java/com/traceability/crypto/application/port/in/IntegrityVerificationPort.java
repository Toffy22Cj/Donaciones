package com.traceability.crypto.application.port.in;

import com.traceability.crypto.domain.VerificationResult;
import java.util.stream.Stream;

public interface IntegrityVerificationPort {
    VerificationResult verifyBatch(String batchId);
    /**
     * Streams the verification results for all ANCHORED batches.
     * The returned stream is backed by a database cursor and MUST be closed by the consumer
     * (e.g. using a try-with-resources block) to avoid resource leaks.
     */
    Stream<VerificationResult> verifyAllAnchored();
}
