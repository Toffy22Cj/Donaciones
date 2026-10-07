package com.traceability.core.application.security;

import java.util.Optional;

public record TrackingCodeValidationResult(boolean valid, Optional<String> fundId, FailureReason failureReason) {
    public enum FailureReason {
        NONE, INVALID_FORMAT, INVALID_HMAC, EXPIRED, REVOKED
    }
}
