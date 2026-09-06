package com.traceability.core.application.security;

import java.time.Instant;

public interface TrackingCodeService {
    String generate(String fundId, Instant expiry);
    TrackingCodeValidationResult validate(String token);
    void revoke(String tokenHash);
}
