package com.traceability.crypto.domain;

import java.util.List;

/**
 * Resultado de verificar un lote. {@code inconclusiveReason} solo con {@code INCONCLUSIVE} (Enmienda 1 de ADR-039
 * §2.2.4): es un componente añadido; el constructor de cinco componentes se mantiene y lo deja en {@code null}.
 */
public record VerificationResult(
    VerificationStatus status,
    String recomputedRoot,
    String expectedRoot,
    List<StreamIdentity> affectedSequences,
    boolean diagnosisComplete,
    InconclusiveReason inconclusiveReason
) {
    public VerificationResult(VerificationStatus status, String recomputedRoot, String expectedRoot,
                              List<StreamIdentity> affectedSequences, boolean diagnosisComplete) {
        this(status, recomputedRoot, expectedRoot, affectedSequences, diagnosisComplete, null);
    }
}
