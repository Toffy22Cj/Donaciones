package com.traceability.ai.domain.narrative;

import java.util.List;

public record LlmNarrativeResponse(
    String narrativeText,
    List<CitedFact> citedFacts
) {
}
