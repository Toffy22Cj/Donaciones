package com.traceability.core.application.saga;

/** Estado nombrado de una división (D-SPLIT S7; plan B1-bis Q3; Enmienda 1 D5). */
public enum SplitResolutionStatus {
    PENDING,
    CHILD_CREATED,
    COMPENSATED,
    UNRESOLVED,
    RESOLVED_MANUALLY
}
