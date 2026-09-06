package com.traceability.core.infrastructure.security;

import com.traceability.core.application.security.TrackingCodeService;
import com.traceability.core.application.security.TrackingCodeValidationResult;
import com.traceability.core.infrastructure.security.properties.TrackingSecurityProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
    "traceability.security.tracking-code-secret=test-secret-key-12345678901234567890",
    "traceability.security.asset-ref-secret=test-secret-key-09876543210987654321"
})
@Testcontainers
public class TrackingCodeServiceIntegrationTest {

    @org.springframework.context.annotation.Configuration
    @org.springframework.boot.autoconfigure.SpringBootApplication(scanBasePackages = {
            "com.traceability.core.infrastructure.security",
            "com.traceability.core.application.security"
    })
    static class TestApp { }

    @Container
    static MongoDBContainer mongoDBContainer = new MongoDBContainer(org.testcontainers.utility.DockerImageName.parse("mongo:6.0"));

    @org.springframework.test.context.DynamicPropertySource
    static void setProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongoDBContainer::getReplicaSetUrl);
    }

    @Autowired
    private TrackingCodeService trackingCodeService;

    @Autowired
    private TrackingSecurityProperties properties;

    @Test
    void testRevocationIntegration() {
        String fundId = "fund-integration-123";
        Instant expiry = Instant.now().plus(1, ChronoUnit.DAYS);

        // 1. Generate token
        String token = trackingCodeService.generate(fundId, expiry);

        // 2. Validate it's initially valid
        TrackingCodeValidationResult initialResult = trackingCodeService.validate(token);
        assertTrue(initialResult.valid());
        assertEquals(Optional.of(fundId), initialResult.fundId());

        // 3. Revoke token
        trackingCodeService.revoke(token);

        // 4. Validate it's now revoked
        TrackingCodeValidationResult revokedResult = trackingCodeService.validate(token);
        assertFalse(revokedResult.valid());
        assertEquals(Optional.empty(), revokedResult.fundId());
        assertEquals(TrackingCodeValidationResult.FailureReason.REVOKED, revokedResult.failureReason());
    }
}
