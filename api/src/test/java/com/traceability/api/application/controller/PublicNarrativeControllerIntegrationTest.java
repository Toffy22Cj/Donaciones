package com.traceability.api.application.controller;

import com.traceability.api.application.mapper.PublicNarrativeMapper;
import com.traceability.api.infrastructure.security.TrackingCodeAuthFilter;
import com.traceability.contracts.NarrativeReadModel;
import com.traceability.contracts.NarrativeReadPort;
import com.traceability.contracts.NarrativeSource;
import com.traceability.contracts.NarrativeStatus;
import com.traceability.core.application.port.out.DonationReadModel;
import com.traceability.core.application.port.out.DonationReadPort;
import com.traceability.core.application.security.TrackingCodeService;
import com.traceability.core.infrastructure.security.HmacTrackingCodeService;
import com.traceability.core.infrastructure.security.mongo.RevokedTrackingCodeRepository;
import com.traceability.core.infrastructure.security.properties.TrackingSecurityProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "traceability.security.tracking-code-secret=test-secret-key-12345678901234567890",
        "traceability.security.asset-ref-secret=test-secret-key-09876543210987654321"
})
@AutoConfigureMockMvc
@Testcontainers
class PublicNarrativeControllerIntegrationTest {

    @SpringBootApplication(scanBasePackages = {
            "com.traceability.api.infrastructure.security",
            "com.traceability.api.application.controller",
            "com.traceability.api.application.mapper"
    })
    @EnableMongoRepositories(basePackages = "com.traceability.core.infrastructure.security.mongo")
    @Import(TrackingSecurityProperties.class)
    static class TestApp {
        @Bean
        public TrackingCodeService trackingCodeService(
                TrackingSecurityProperties properties,
                RevokedTrackingCodeRepository repository) {
            return new HmacTrackingCodeService(properties, repository);
        }
    }

    @Container
    static MongoDBContainer mongoDBContainer = new MongoDBContainer(org.testcontainers.utility.DockerImageName.parse("mongo:6.0"));

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongoDBContainer::getReplicaSetUrl);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TrackingCodeService trackingCodeService;

    @MockitoBean
    private DonationReadPort donationReadPort;

    @MockitoBean
    private NarrativeReadPort narrativeReadPort;

    @MockitoBean
    private com.traceability.core.application.security.AssetRefService assetRefService;

    @MockitoBean
    private com.traceability.core.application.service.LocationReferenceService locationReferenceService;

    @MockitoBean
    private com.traceability.core.application.port.out.AssetAuthorizationPort assetAuthorizationPort;

    @MockitoBean
    private com.traceability.core.application.port.out.AssetHistoryReadPort assetHistoryReadPort;


    @Test
    void shouldReturn404WhenFundHasNoDonationProjection() throws Exception {
        String fundId = "fund-no-projection";
        String validToken = trackingCodeService.generate(fundId, Instant.now().plus(1, ChronoUnit.HOURS));

        // Consistencia eventual: el fondo aún no tiene proyecciones en DonationReadPort
        when(donationReadPort.findByFundId(fundId)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/donations/tracking/narrative")
                        .header("Authorization", "Bearer " + validToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldReturn202PendingWhenNarrativeIsPending() throws Exception {
        String fundId = "fund-pending";
        String validToken = trackingCodeService.generate(fundId, Instant.now().plus(1, ChronoUnit.HOURS));

        DonationReadModel mockModel = new DonationReadModel(fundId, "USD", "camp-1", 100L, 100L, 0L, 0L, 0L, "ACTIVE", List.of());
        when(donationReadPort.findByFundId(fundId)).thenReturn(Optional.of(mockModel));
        
        NarrativeReadModel pendingModel = new NarrativeReadModel(NarrativeStatus.PENDING, null, null);
        when(narrativeReadPort.getOrTriggerGeneration(fundId)).thenReturn(Optional.of(pendingModel));

        mockMvc.perform(get("/api/v1/donations/tracking/narrative")
                        .header("Authorization", "Bearer " + validToken))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.content").doesNotExist());
    }

    @Test
    void shouldReturn200AvailableWhenNarrativeIsReady() throws Exception {
        String fundId = "fund-available";
        String validToken = trackingCodeService.generate(fundId, Instant.now().plus(1, ChronoUnit.HOURS));

        DonationReadModel mockModel = new DonationReadModel(fundId, "USD", "camp-1", 100L, 100L, 0L, 0L, 0L, "ACTIVE", List.of());
        when(donationReadPort.findByFundId(fundId)).thenReturn(Optional.of(mockModel));
        
        NarrativeReadModel availableModel = new NarrativeReadModel(NarrativeStatus.AVAILABLE, "The quick brown fox", NarrativeSource.LLM_GENERATED);
        when(narrativeReadPort.getOrTriggerGeneration(fundId)).thenReturn(Optional.of(availableModel));

        mockMvc.perform(get("/api/v1/donations/tracking/narrative")
                        .header("Authorization", "Bearer " + validToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.content").value("The quick brown fox"))
                .andExpect(jsonPath("$.source").value("LLM_GENERATED"));
    }

    @Test
    void shouldReturn401WhenMissingHeader() throws Exception {
        mockMvc.perform(get("/api/v1/donations/tracking/narrative"))
                .andExpect(status().isUnauthorized());
    }
}
