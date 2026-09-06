package com.traceability.core.infrastructure.security;

import com.traceability.core.application.security.TrackingCodeService;
import com.traceability.core.application.security.TrackingCodeValidationResult;
import com.traceability.core.infrastructure.security.mongo.RevokedTrackingCodeDocument;
import com.traceability.core.infrastructure.security.mongo.RevokedTrackingCodeRepository;
import com.traceability.core.infrastructure.security.properties.TrackingSecurityProperties;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

@Service
public class HmacTrackingCodeService implements TrackingCodeService {

    private static final String HMAC_ALGO = "HmacSHA256";
    private static final String PREFIX = "tracking:v1:";

    private final TrackingSecurityProperties properties;
    private final RevokedTrackingCodeRepository revokedRepository;

    public HmacTrackingCodeService(TrackingSecurityProperties properties, RevokedTrackingCodeRepository revokedRepository) {
        this.properties = properties;
        this.revokedRepository = revokedRepository;
    }

    @Override
    public String generate(String fundId, Instant expiry) {
        String payload = fundId + "|" + expiry.toEpochMilli();
        String payloadB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        
        String dataToSign = PREFIX + payload;
        byte[] hmac = computeHmac(dataToSign);
        String hmacB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(hmac);
        
        return payloadB64 + "." + hmacB64;
    }

    @Override
    public TrackingCodeValidationResult validate(String token) {
        if (token == null || token.isBlank()) {
            return new TrackingCodeValidationResult(false, Optional.empty(), TrackingCodeValidationResult.FailureReason.INVALID_FORMAT);
        }

        String[] parts = token.split("\\.");
        if (parts.length != 2) {
            return new TrackingCodeValidationResult(false, Optional.empty(), TrackingCodeValidationResult.FailureReason.INVALID_FORMAT);
        }

        String payloadB64 = parts[0];
        String signatureB64 = parts[1];

        String payload;
        try {
            payload = new String(Base64.getUrlDecoder().decode(payloadB64), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return new TrackingCodeValidationResult(false, Optional.empty(), TrackingCodeValidationResult.FailureReason.INVALID_FORMAT);
        }

        String[] payloadParts = payload.split("\\|");
        if (payloadParts.length != 2) {
            return new TrackingCodeValidationResult(false, Optional.empty(), TrackingCodeValidationResult.FailureReason.INVALID_FORMAT);
        }

        String fundId = payloadParts[0];
        long expiryMillis;
        try {
            expiryMillis = Long.parseLong(payloadParts[1]);
        } catch (NumberFormatException e) {
            return new TrackingCodeValidationResult(false, Optional.empty(), TrackingCodeValidationResult.FailureReason.INVALID_FORMAT);
        }

        // Verify HMAC
        String dataToSign = PREFIX + payload;
        byte[] expectedHmac = computeHmac(dataToSign);
        byte[] providedHmac;
        try {
            providedHmac = Base64.getUrlDecoder().decode(signatureB64);
        } catch (IllegalArgumentException e) {
            return new TrackingCodeValidationResult(false, Optional.empty(), TrackingCodeValidationResult.FailureReason.INVALID_FORMAT);
        }

        if (!MessageDigest.isEqual(expectedHmac, providedHmac)) {
            return new TrackingCodeValidationResult(false, Optional.empty(), TrackingCodeValidationResult.FailureReason.INVALID_HMAC);
        }

        // Verify Expiry
        if (Instant.now().isAfter(Instant.ofEpochMilli(expiryMillis))) {
            return new TrackingCodeValidationResult(false, Optional.empty(), TrackingCodeValidationResult.FailureReason.EXPIRED);
        }

        // Verify Revocation
        String tokenHash = computeSha256Hex(token);
        if (revokedRepository.existsById(tokenHash)) {
            return new TrackingCodeValidationResult(false, Optional.empty(), TrackingCodeValidationResult.FailureReason.REVOKED);
        }

        return new TrackingCodeValidationResult(true, Optional.of(fundId), TrackingCodeValidationResult.FailureReason.NONE);
    }

    @Override
    public void revoke(String token) {
        String tokenHash = computeSha256Hex(token);
        revokedRepository.save(new RevokedTrackingCodeDocument(tokenHash, Instant.now()));
    }

    private byte[] computeHmac(String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            SecretKeySpec secretKeySpec = new SecretKeySpec(properties.getTrackingCodeSecret().getBytes(StandardCharsets.UTF_8), HMAC_ALGO);
            mac.init(secretKeySpec);
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException("Error computing HMAC", e);
        }
    }

    private String computeSha256Hex(String data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder(2 * hash.length);
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Error computing SHA-256", e);
        }
    }
}
