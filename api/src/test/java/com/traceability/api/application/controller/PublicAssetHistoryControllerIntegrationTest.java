package com.traceability.api.application.controller;

import com.traceability.api.application.dto.AssetHistoryPublicDTO;
import com.traceability.core.application.port.out.AssetAuthorizationPort;
import com.traceability.core.application.security.AssetRefService;
import com.traceability.core.infrastructure.projection.mongo.documents.DonationProjectionDocument;
import com.traceability.core.application.security.TrackingCodeService;
import com.traceability.core.infrastructure.projection.mongo.documents.AssetHistoryProjectionDocument;
import com.traceability.core.infrastructure.projection.mongo.documents.LocationReferenceDocument;
import com.traceability.core.infrastructure.projection.mongo.repositories.AssetHistoryProjectionRepository;
import com.traceability.core.infrastructure.projection.mongo.repositories.DonationProjectionRepository;
import com.traceability.core.infrastructure.projection.mongo.repositories.LocationReferenceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import com.traceability.core.infrastructure.security.HmacTrackingCodeService;
import com.traceability.core.infrastructure.security.mongo.RevokedTrackingCodeRepository;
import com.traceability.core.infrastructure.security.properties.TrackingSecurityProperties;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "traceability.security.tracking-code-secret=test-secret-key-12345678901234567890",
        "traceability.security.asset-ref-secret=test-secret-key-09876543210987654321"
})
@AutoConfigureMockMvc
@Testcontainers
class PublicAssetHistoryControllerIntegrationTest {

    @SpringBootApplication(scanBasePackages = {
            "com.traceability.api.infrastructure.security",
            "com.traceability.api.application.controller",
            "com.traceability.api.application.mapper",
            "com.traceability.core.application.security",
            "com.traceability.core.infrastructure.projection.mongo"
    })
    @EnableMongoRepositories(basePackages = {
            "com.traceability.core.infrastructure.security.mongo",
            "com.traceability.core.infrastructure.projection.mongo.repositories"
    })
    @Import(TrackingSecurityProperties.class)
    static class TestApp {
        @Bean
        public TrackingCodeService trackingCodeService(
                TrackingSecurityProperties properties,
                RevokedTrackingCodeRepository repository) {
            return new HmacTrackingCodeService(properties, repository);
        }

        @Bean
        public AssetRefService assetRefService(TrackingSecurityProperties properties) {
            return new com.traceability.core.infrastructure.security.HmacAssetRefService(properties);
        }

        @Bean
        public com.traceability.core.application.service.LocationReferenceService locationReferenceService(
                com.traceability.core.infrastructure.projection.mongo.repositories.LocationReferenceRepository repository) {
            return new com.traceability.core.application.service.LocationReferenceService(repository);
        }
    }

    @Container
    static final MongoDBContainer mongoDBContainer = new MongoDBContainer(DockerImageName.parse("mongo:6.0"));

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongoDBContainer::getReplicaSetUrl);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TrackingCodeService trackingCodeService;

    @Autowired
    private DonationProjectionRepository donationReadRepository;

    @Autowired
    private AssetHistoryProjectionRepository assetHistoryProjectionRepository;

    @Autowired
    private LocationReferenceRepository locationReferenceRepository;

    @MockBean
    private AssetAuthorizationPort assetAuthorizationPort;

    @MockBean
    private com.traceability.contracts.NarrativeReadPort narrativeReadPort;

    @BeforeEach
    void setUp() {
        donationReadRepository.deleteAll();
        assetHistoryProjectionRepository.deleteAll();
        locationReferenceRepository.deleteAll();
    }

    @Autowired
    private AssetRefService assetRefService;

    @Test
    void shouldReturn200EndToEndWithValidToken() throws Exception {
        String fundId = "fund-123";
        String token = trackingCodeService.generate(fundId, Instant.now().plusSeconds(3600));
        String assetId = "asset-001";
        
        // Generate valid assetRef
        String assetRef = assetRefService.computeRef(assetId);

        // Setup logistics
        DonationProjectionDocument.LogisticsProjection item = DonationProjectionDocument.LogisticsProjection.builder()
                .assetId(assetId)
                .lifecycleStatus("REGISTERED")
                .assetType("Food")
                .unitOfMeasure("kg")
                .quantity(java.math.BigDecimal.TEN)
                .currentLocation("LOC-1")
                .currentCustodian("REGISTERED")
                .build();
        
        DonationProjectionDocument donationModel = DonationProjectionDocument.builder()
                .projectionId(fundId)
                .currency("USD")
                .campaignRef("CAM-1")
                .financialSnapshot(DonationProjectionDocument.FinancialSnapshot.builder()
                        .originalAmount(1000)
                        .clearedAmount(1000)
                        .pendingAllocationAmount(0)
                        .refundedAmount(0)
                        .build())
                .status("CLEARED")
                .logistics(List.of(item))
                .build();
        donationReadRepository.save(donationModel);

        // Setup location
        locationReferenceRepository.save(new LocationReferenceDocument("LOC-1", "Zone X"));

        // Setup history
        AssetHistoryProjectionDocument.AssetTransition transition = AssetHistoryProjectionDocument.AssetTransition.builder()
                .eventType("AssetRegisteredEvent")
                .timestamp("2026-09-06T10:00:00Z")
                .location("LOC-1")
                .custodian("REGISTERED")
                .status("OK")
                .sequence(1L)
                .build();
        AssetHistoryProjectionDocument historyModel = AssetHistoryProjectionDocument.builder()
                .assetId(assetId)
                .transitions(List.of(transition))
                .build();
        assetHistoryProjectionRepository.save(historyModel);

        // Mock authorization
        Mockito.when(assetAuthorizationPort.assetBelongsToFund(assetId, fundId)).thenReturn(true);

        mockMvc.perform(get("/api/v1/donations/tracking/assets/" + assetRef + "/history")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.history[0].eventType").value("AssetRegisteredEvent"))
                .andExpect(jsonPath("$.history[0].locationZone").value("Zone X"))
                .andExpect(jsonPath("$.history[0].custodianCategory").value("UNCATEGORIZED"));
    }

    @Test
    void shouldReturn401EndToEndWithValidTokenButMissingModel() throws Exception {
        String fundId = "fund-123";
        String token = trackingCodeService.generate(fundId, Instant.now().plusSeconds(3600));
        
        // DonationReadModel is missing!

        mockMvc.perform(get("/api/v1/donations/tracking/assets/anyRef/history")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Invalid or missing token"));
    }

    @Test
    void shouldReturn401EndToEndWithInvalidAssetRef() throws Exception {
        String fundId = "fund-123";
        String token = trackingCodeService.generate(fundId, Instant.now().plusSeconds(3600));
        
        // Setup logistics with different asset
        DonationProjectionDocument.LogisticsProjection item = DonationProjectionDocument.LogisticsProjection.builder()
                .assetId("asset-999")
                .lifecycleStatus("REGISTERED")
                .assetType("Food")
                .unitOfMeasure("kg")
                .quantity(java.math.BigDecimal.TEN)
                .currentLocation("LOC-1")
                .currentCustodian("REGISTERED")
                .build();
        
        DonationProjectionDocument donationModel = DonationProjectionDocument.builder()
                .projectionId(fundId)
                .currency("USD")
                .campaignRef("CAM-1")
                .financialSnapshot(DonationProjectionDocument.FinancialSnapshot.builder()
                        .originalAmount(1000)
                        .clearedAmount(1000)
                        .pendingAllocationAmount(0)
                        .refundedAmount(0)
                        .build())
                .status("CLEARED")
                .logistics(List.of(item))
                .build();
        donationReadRepository.save(donationModel);

        // Provide an assetRef that doesn't resolve with the candidates
        mockMvc.perform(get("/api/v1/donations/tracking/assets/invalid-ref/history")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Invalid or missing token"));
    }

    @Test
    void shouldReturn401EndToEndWithValidAssetRefButNotAuthorized() throws Exception {
        String fundId = "fund-123";
        String token = trackingCodeService.generate(fundId, Instant.now().plusSeconds(3600));
        String assetId = "asset-001";
        String assetRef = assetRefService.computeRef(assetId);

        DonationProjectionDocument.LogisticsProjection item = DonationProjectionDocument.LogisticsProjection.builder()
                .assetId(assetId)
                .lifecycleStatus("REGISTERED")
                .assetType("Food")
                .unitOfMeasure("kg")
                .quantity(java.math.BigDecimal.TEN)
                .currentLocation("LOC-1")
                .currentCustodian("REGISTERED")
                .build();
        
        DonationProjectionDocument donationModel = DonationProjectionDocument.builder()
                .projectionId(fundId)
                .currency("USD")
                .campaignRef("CAM-1")
                .financialSnapshot(DonationProjectionDocument.FinancialSnapshot.builder()
                        .originalAmount(1000)
                        .clearedAmount(1000)
                        .pendingAllocationAmount(0)
                        .refundedAmount(0)
                        .build())
                .status("CLEARED")
                .logistics(List.of(item))
                .build();
        donationReadRepository.save(donationModel);

        // Resolution works, but authorization fails
        Mockito.when(assetAuthorizationPort.assetBelongsToFund(assetId, fundId)).thenReturn(false);

        mockMvc.perform(get("/api/v1/donations/tracking/assets/" + assetRef + "/history")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Invalid or missing token"));
    }

    @Test
    void shouldReturn401EndToEndWithoutAuthorizationHeader() throws Exception {
        mockMvc.perform(get("/api/v1/donations/tracking/assets/some-ref/history"))
                .andExpect(status().isUnauthorized());
    }
}
