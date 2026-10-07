package com.traceability.core.infrastructure.security.mongo;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "revoked_tracking_codes")
public class RevokedTrackingCodeDocument {
    @Id
    private String tokenHash;

    @Indexed(expireAfterSeconds = 31536000)
    private Instant revokedAt;

    public RevokedTrackingCodeDocument() {
    }

    public RevokedTrackingCodeDocument(String tokenHash, Instant revokedAt) {
        this.tokenHash = tokenHash;
        this.revokedAt = revokedAt;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public void setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(Instant revokedAt) {
        this.revokedAt = revokedAt;
    }
}
