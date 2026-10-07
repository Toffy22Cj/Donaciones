package com.traceability.core.infrastructure.security;

import com.traceability.core.application.security.TrackingCodeValidationResult;
import com.traceability.core.infrastructure.security.mongo.RevokedTrackingCodeRepository;
import com.traceability.core.infrastructure.security.properties.TrackingSecurityProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HmacTrackingCodeServiceTest {

    @Mock
    private RevokedTrackingCodeRepository revokedRepository;

    private HmacTrackingCodeService service;

    @BeforeEach
    void setUp() {
        TrackingSecurityProperties properties = new TrackingSecurityProperties();
        properties.setTrackingCodeSecret("test-secret-key-12345678901234567890");
        service = new HmacTrackingCodeService(properties, revokedRepository);
    }

    @Test
    void testGenerateAndValidate_ValidToken() {
        when(revokedRepository.existsById(anyString())).thenReturn(false);

        String fundId = "fund-123";
        Instant expiry = Instant.now().plus(1, ChronoUnit.DAYS);

        String token = service.generate(fundId, expiry);

        TrackingCodeValidationResult result = service.validate(token);

        assertTrue(result.valid());
        assertEquals(Optional.of(fundId), result.fundId());
        assertEquals(TrackingCodeValidationResult.FailureReason.NONE, result.failureReason());
    }

    @Test
    void testValidate_ExpiredToken() {
        String fundId = "fund-123";
        Instant expiry = Instant.now().minus(1, ChronoUnit.DAYS); // Already expired

        String token = service.generate(fundId, expiry);

        TrackingCodeValidationResult result = service.validate(token);

        assertFalse(result.valid());
        assertEquals(Optional.empty(), result.fundId());
        assertEquals(TrackingCodeValidationResult.FailureReason.EXPIRED, result.failureReason());
    }

    @Test
    void testValidate_InvalidFormat() {
        TrackingCodeValidationResult result1 = service.validate("invalid_token_no_dot");
        assertFalse(result1.valid());
        assertEquals(TrackingCodeValidationResult.FailureReason.INVALID_FORMAT, result1.failureReason());

        TrackingCodeValidationResult result2 = service.validate(null);
        assertFalse(result2.valid());
        assertEquals(TrackingCodeValidationResult.FailureReason.INVALID_FORMAT, result2.failureReason());

        TrackingCodeValidationResult result3 = service.validate("");
        assertFalse(result3.valid());
        assertEquals(TrackingCodeValidationResult.FailureReason.INVALID_FORMAT, result3.failureReason());
    }

    @Test
    void testValidate_InvalidHmac() {
        String fundId = "fund-123";
        Instant expiry = Instant.now().plus(1, ChronoUnit.DAYS);

        String token = service.generate(fundId, expiry);
        
        // Tamper with the signature part
        String[] parts = token.split("\\.");
        String tamperedToken = parts[0] + ".tampered" + parts[1];

        TrackingCodeValidationResult result = service.validate(tamperedToken);

        assertFalse(result.valid());
        assertEquals(Optional.empty(), result.fundId());
        assertEquals(TrackingCodeValidationResult.FailureReason.INVALID_HMAC, result.failureReason());
    }
    
    @Test
    void testValidate_InvalidPayload_TamperedData() {
        String fundId = "fund-123";
        Instant expiry = Instant.now().plus(1, ChronoUnit.DAYS);

        String token = service.generate(fundId, expiry);
        
        // Tamper with the payload part
        String[] parts = token.split("\\.");
        // Re-encode payload with a different fundId
        String newPayload = "fund-999|" + expiry.toEpochMilli();
        String tamperedPayloadB64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(newPayload.getBytes());
        String tamperedToken = tamperedPayloadB64 + "." + parts[1];

        TrackingCodeValidationResult result = service.validate(tamperedToken);

        // Should fail HMAC validation because signature doesn't match the new payload
        assertFalse(result.valid());
        assertEquals(Optional.empty(), result.fundId());
        assertEquals(TrackingCodeValidationResult.FailureReason.INVALID_HMAC, result.failureReason());
    }
}
