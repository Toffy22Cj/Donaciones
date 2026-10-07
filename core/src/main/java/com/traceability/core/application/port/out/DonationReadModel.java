package com.traceability.core.application.port.out;

import java.util.List;

public record DonationReadModel(
    String fundId,
    String currency,
    String campaignRef,
    long originalAmount,
    long clearedAmount,
    long pendingAllocationAmount,
    long confirmedAllocationAmount,
    long refundedAmount,
    String status,
    List<LogisticsReadItem> logistics
) {
}
