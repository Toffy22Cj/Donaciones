package com.traceability.core.infrastructure.security;

import com.traceability.core.application.security.AssetRefService;
import com.traceability.core.infrastructure.security.properties.TrackingSecurityProperties;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Collection;
import java.util.Optional;

@Service
public class HmacAssetRefService implements AssetRefService {

    private static final String ALGORITHM = "HmacSHA256";
    private static final String PREFIX = "asset-ref:v1:";

    private final String secret;

    public HmacAssetRefService(TrackingSecurityProperties properties) {
        this.secret = properties.getAssetRefSecret();
    }

    @Override
    public String computeRef(String assetId) {
        if (assetId == null || assetId.isBlank()) {
            throw new IllegalArgumentException("AssetId cannot be null or empty");
        }
        
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            SecretKeySpec secretKey = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
            mac.init(secretKey);
            
            String payload = PREFIX + assetId;
            byte[] hmacBytes = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hmacBytes);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to calculate HMAC for assetRef", e);
        }
    }

    @Override
    public Optional<String> resolveAssetId(String assetRef, Collection<String> candidateAssetIds) {
        if (assetRef == null || assetRef.isBlank() || candidateAssetIds == null || candidateAssetIds.isEmpty()) {
            return Optional.empty();
        }

        byte[] expectedRefBytes = assetRef.getBytes(StandardCharsets.UTF_8);

        for (String candidate : candidateAssetIds) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            
            String candidateRef = computeRef(candidate);
            
            // Mitigates timing attacks when matching the given reference
            if (MessageDigest.isEqual(candidateRef.getBytes(StandardCharsets.UTF_8), expectedRefBytes)) {
                return Optional.of(candidate);
            }
        }
        
        return Optional.empty();
    }
}
