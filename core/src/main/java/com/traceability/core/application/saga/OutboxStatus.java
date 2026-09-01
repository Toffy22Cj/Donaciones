package com.traceability.core.application.saga;

public enum OutboxStatus {
    PENDING,
    QUARANTINED,
    COMPLETED
}
