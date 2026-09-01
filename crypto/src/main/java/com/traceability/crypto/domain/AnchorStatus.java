package com.traceability.crypto.domain;

public enum AnchorStatus {
    PENDING,
    SUBMITTING,
    SUBMITTED,
    ANCHORED,
    STUCK,
    FAILED,
    ANCHOR_MISMATCH
}
