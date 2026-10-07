package com.traceability.crypto.domain;

import java.util.List;

public record VerificationResult(
    VerificationStatus status,
    String recomputedRoot,
    String expectedRoot,
    List<StreamIdentity> affectedSequences,
    boolean diagnosisComplete
) {
}
