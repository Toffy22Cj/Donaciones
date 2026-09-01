package com.traceability.ai.domain.narrative;

import java.time.Instant;

public record DonorReportDTO(
    String narrativeText,
    NarrativeSource source,
    String modelIdentifier,
    String promptTemplateVersion,
    String sourceFactsHash,
    long auditFactsSequence,
    Instant generatedAt,
    Instant nextRetryAt
) {
}
