package com.traceability.contracts;

import java.util.Map;

/**
 * Port for cryptographic hashing operations.
 * 
 * Ref: ADR-015 (Aislamiento de infraestructura)
 */
public interface HashPort {
    /**
     * Canonicalizes event data according to RFC 8785 and generates a cryptographic hash.
     */
    String canonicalizeAndHash(Map<String, Object> eventData, String previousHash);
}
