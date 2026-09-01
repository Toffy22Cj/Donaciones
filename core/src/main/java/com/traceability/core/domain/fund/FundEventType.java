package com.traceability.core.domain.fund;

import com.traceability.core.domain.event.EventType;

public enum FundEventType implements EventType {
    FUND_REGISTERED,
    FUNDS_CLEARED,
    ALLOCATION_REQUESTED,
    ALLOCATION_CONFIRMED,
    ALLOCATION_REVERSED,
    FUNDS_REFUNDED
}
