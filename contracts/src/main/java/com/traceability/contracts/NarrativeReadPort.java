package com.traceability.contracts;

import java.util.Optional;

public interface NarrativeReadPort {
    Optional<NarrativeReadModel> getOrTriggerGeneration(String fundId);
}
