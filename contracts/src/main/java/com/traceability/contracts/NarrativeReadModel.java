package com.traceability.contracts;

public record NarrativeReadModel(
        NarrativeStatus status,
        String content,
        NarrativeSource source
) {}
