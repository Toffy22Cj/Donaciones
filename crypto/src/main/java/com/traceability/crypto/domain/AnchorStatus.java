package com.traceability.crypto.domain;

public enum AnchorStatus {
    COLLECTING,
    PENDING,
    SUBMITTING,
    SUBMITTED,
    ANCHORED,
    STUCK,
    FAILED,
    ANCHOR_MISMATCH
}
