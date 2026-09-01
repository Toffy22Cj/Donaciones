package com.traceability.ai.domain.narrative;

public record CacheKey(
    String donationId,
    long auditFactsSequence,
    String sourceFactsHash,
    String promptTemplateVersion,
    String modelIdentifier
) {
}
