package com.traceability.core.application.port.out;

import java.time.Instant;

public record AssetTransitionReadModel(
        String eventType,
        String status,
        String custodian,
        String location,
        Instant timestamp
) {
}
