package com.traceability.api.application.controller;

import com.traceability.api.application.mapper.PublicDonationMapper;
import com.traceability.api.infrastructure.security.TrackingCodeAuthFilter;
import com.traceability.core.application.port.out.DonationReadModel;
import com.traceability.core.application.port.out.DonationReadPort;
import com.traceability.core.application.port.out.AssetAuthorizationPort;
import com.traceability.core.application.security.AssetRefService;
import com.traceability.core.application.security.TrackingCodeService;
import com.traceability.core.application.service.LocationReferenceService;
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
class PublicDonationControllerIntegrationTest {

    @SpringBootApplication(scanBasePackages = {
            "com.traceability.api.infrastructure.security",
            "com.traceability.api.application.controller",
            "com.traceability.api.application.mapper"
    })
    @EnableMongoRepositories(basePackages = "com.traceability.core.infrastructure.security.mongo")
    // el manejador único de errores de B6-0, como en la aplicación (TR-D1: el 404 del seguimiento lo compone él)
    @Import({TrackingSecurityProperties.class, com.traceability.api.web.ApiExceptionHandler.class,
            com.traceability.api.web.ApiBaseErrorMappings.class})
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

    // Mocking the core ports since we only want to test API integration (Filter + Controller)
    @MockitoBean
    private DonationReadPort donationReadPort;
    @MockitoBean
    private AssetRefService assetRefService;
    @MockitoBean
    private LocationReferenceService locationReferenceService;
    @MockitoBean
    private AssetAuthorizationPort assetAuthorizationPort;
    @MockitoBean
    private com.traceability.core.application.port.out.AssetHistoryReadPort assetHistoryReadPort;
    @MockitoBean
    private com.traceability.contracts.NarrativeReadPort narrativeReadPort;
    // Doble del puerto de la ficha R-6 (G-1): api se prueba sin app, que es quien lo implementa
    @MockitoBean
    private com.traceability.contracts.campaign.CampaignPublicSummaryPort campaignSummaries;
    // PublicDonationMapper lo necesita (ficha R-6, G-2); lo implementa core, que este test no levanta entero
    @MockitoBean
    private com.traceability.core.application.port.out.FundClearingDatesReadPort clearingDates;

    @Test
    void shouldReturn200EndToEndWithValidToken() throws Exception {
        String fundId = "fund-real-end-to-end";
        String validToken = trackingCodeService.generate(fundId, Instant.now().plus(1, ChronoUnit.HOURS));

        DonationReadModel mockModel = new DonationReadModel(fundId, "USD", "camp-1", 100L, 100L, 0L, 0L, 0L, "ACTIVE", List.of());
        when(donationReadPort.findByFundId(fundId)).thenReturn(Optional.of(mockModel));
        when(campaignSummaries.findByCampaignRef("camp-1")).thenReturn(Optional.of(
                new com.traceability.contracts.campaign.CampaignPublicSummaryPort.CampaignPublicSummary("Invierno",
                        "Fundación Uno")));

        mockMvc.perform(get("/api/v1/donations/tracking")
                        .header("Authorization", "Bearer " + validToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.campaignRef").value("camp-1"))
                .andExpect(jsonPath("$.status").value("ACTIVA"))
                .andExpect(jsonPath("$.financialSnapshot.currency").value("USD"))
                // ficha R-6, G-1; DD-76: ni el fondo ni el donante
                .andExpect(jsonPath("$.campaignTitle").value("Invierno"))
                .andExpect(jsonPath("$.organizationName").value("Fundación Uno"))
                .andExpect(jsonPath("$.fundId").doesNotExist())
                .andExpect(jsonPath("$.donorRef").doesNotExist())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(fundId))));
    }

    @Test
    void withoutCampaign_theTrackingHasNeitherTitleNorOrganization() throws Exception {
        String fundId = "fund-without-campaign";
        String validToken = trackingCodeService.generate(fundId, Instant.now().plus(1, ChronoUnit.HOURS));
        when(donationReadPort.findByFundId(fundId)).thenReturn(Optional.of(
                new DonationReadModel(fundId, "USD", null, 100L, 100L, 0L, 0L, 0L, "ACTIVE", List.of())));

        mockMvc.perform(get("/api/v1/donations/tracking")
                        .header("Authorization", "Bearer " + validToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.campaignRef").doesNotExist())
                .andExpect(jsonPath("$.campaignTitle").doesNotExist())
                .andExpect(jsonPath("$.organizationName").doesNotExist());
    }

    @Test
    void shouldReturn404EndToEndWithValidTokenButMissingModel() throws Exception {
        String fundId = "fund-real-missing";
        String validToken = trackingCodeService.generate(fundId, Instant.now().plus(1, ChronoUnit.HOURS));

        when(donationReadPort.findByFundId(fundId)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/donations/tracking")
                        .header("Authorization", "Bearer " + validToken))
                .andExpect(status().isNotFound())
                // TR-D1 (Q-B60-1, corregido en B6-d)
                .andExpect(jsonPath("$.title").value("NotFound"));
    }

    @Test
    void shouldReturn401EndToEndWithoutAuthorizationHeader() throws Exception {
        mockMvc.perform(get("/api/v1/donations/tracking"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldReturn401EndToEndWithRevokedToken() throws Exception {
        String fundId = "fund-revoked";
        String revokedToken = trackingCodeService.generate(fundId, Instant.now().plus(1, ChronoUnit.HOURS));
        trackingCodeService.revoke(revokedToken);

        mockMvc.perform(get("/api/v1/donations/tracking")
                        .header("Authorization", "Bearer " + revokedToken))
                .andExpect(status().isUnauthorized());
    }
}
