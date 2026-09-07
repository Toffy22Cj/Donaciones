package com.traceability.api.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.core.application.security.TrackingCodeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

import com.traceability.core.infrastructure.security.HmacTrackingCodeService;
import com.traceability.core.infrastructure.security.properties.TrackingSecurityProperties;
import com.traceability.core.infrastructure.security.mongo.RevokedTrackingCodeRepository;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

import org.springframework.context.annotation.Import;

@SpringBootTest(properties = {
        "traceability.security.tracking-code-secret=test-secret-key-12345678901234567890",
        "traceability.security.asset-ref-secret=test-secret-key-09876543210987654321"
})
@AutoConfigureMockMvc
@Testcontainers
public class TrackingCodeAuthFilterIntegrationTest {

    @org.springframework.boot.autoconfigure.SpringBootApplication(scanBasePackages = "com.traceability.api.infrastructure.security")
    @EnableMongoRepositories(basePackages = "com.traceability.core.infrastructure.security.mongo")
    @Import(TrackingSecurityProperties.class)
    static class TestApp {
        @Bean
        public TrackingCodeService trackingCodeService(
                TrackingSecurityProperties properties, 
                RevokedTrackingCodeRepository repository) {
            return new HmacTrackingCodeService(properties, repository);
        }

        @RestController
        @RequestMapping("/api/v1/donations/tracking/test")
        public static class TestController {
            @GetMapping
            public ResponseEntity<String> testEndpoint(@RequestAttribute(TrackingCodeAuthFilter.FUND_ID_ATTRIBUTE) String fundId) {
                return ResponseEntity.ok(fundId);
            }
        }
    }

    @Container
    static MongoDBContainer mongoDBContainer = new MongoDBContainer(org.testcontainers.utility.DockerImageName.parse("mongo:6.0"));

    @org.springframework.test.context.DynamicPropertySource
    static void setProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongoDBContainer::getReplicaSetUrl);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TrackingCodeService trackingCodeService;

    @Autowired
    private ObjectMapper objectMapper;

    private String getMissingHeaderBody() throws Exception {
        return mockMvc.perform(get("/api/v1/donations/tracking/test"))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void shouldReturnUnauthorizedWhenHeaderMissing() throws Exception {
        mockMvc.perform(get("/api/v1/donations/tracking/test"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldReturnUnauthorizedWhenTokenMalformed() throws Exception {
        String fundId = "test-fund-123";
        String validToken = trackingCodeService.generate(fundId, Instant.now().plus(1, ChronoUnit.HOURS));
        String malformedToken = validToken.replace(".", "");

        String bodyMalformed = mockMvc.perform(get("/api/v1/donations/tracking/test")
                        .header("Authorization", "Bearer " + malformedToken))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertEquals(getMissingHeaderBody(), bodyMalformed, "Malformed header must match missing header body");
    }

    @Test
    void shouldReturnUnauthorizedWhenHmacAltered() throws Exception {
        String fundId = "test-fund-123";
        String validToken = trackingCodeService.generate(fundId, Instant.now().plus(1, ChronoUnit.HOURS));
        String alteredToken = validToken.substring(0, validToken.length() - 5) + "abcde";

        String bodyAltered = mockMvc.perform(get("/api/v1/donations/tracking/test")
                        .header("Authorization", "Bearer " + alteredToken))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertEquals(getMissingHeaderBody(), bodyAltered, "Altered HMAC must match missing header body");
    }

    @Test
    void shouldReturnUnauthorizedWhenTokenExpired() throws Exception {
        String fundId = "test-fund-123";
        String expiredToken = trackingCodeService.generate(fundId, Instant.now().minus(1, ChronoUnit.HOURS));

        String bodyExpired = mockMvc.perform(get("/api/v1/donations/tracking/test")
                        .header("Authorization", "Bearer " + expiredToken))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertEquals(getMissingHeaderBody(), bodyExpired, "Expired token must match missing header body");
    }

    @Test
    void shouldReturnUnauthorizedWhenTokenRevoked() throws Exception {
        String fundId = "test-fund-123";
        String revokedToken = trackingCodeService.generate(fundId, Instant.now().plus(1, ChronoUnit.HOURS));
        trackingCodeService.revoke(revokedToken);

        String bodyRevoked = mockMvc.perform(get("/api/v1/donations/tracking/test")
                        .header("Authorization", "Bearer " + revokedToken))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertEquals(getMissingHeaderBody(), bodyRevoked, "Revoked token must match missing header body");
    }

    @Test
    void shouldReturnOkWhenTokenValid() throws Exception {
        String fundId = "test-fund-123";
        String validToken = trackingCodeService.generate(fundId, Instant.now().plus(1, ChronoUnit.HOURS));

        mockMvc.perform(get("/api/v1/donations/tracking/test")
                        .header("Authorization", "Bearer " + validToken))
                .andExpect(status().isOk())
                .andExpect(content().string(fundId));
    }
}
