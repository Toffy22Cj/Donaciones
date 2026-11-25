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
import java.util.Optional;
import org.springframework.test.web.servlet.MvcResult;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
class PublicEvidencePreviewControllerIntegrationTest {

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
    // ADR-029 Enmienda 2, D5: los mappers leen la zona con PublicZoneResolver (sobre el LocationReferenceService de abajo)
    @Import({TrackingSecurityProperties.class, com.traceability.core.application.service.PublicZoneResolver.class})
    static class TestApp {
        /** El 404 fijo del seguimiento (en producción lo traduce ApiExceptionHandler; aquí no se levanta). */
        @org.springframework.web.bind.annotation.RestControllerAdvice
        static class NotFoundAdvice {
            @org.springframework.web.bind.annotation.ExceptionHandler(TrackedResourceNotFoundException.class)
            org.springframework.http.ResponseEntity<org.springframework.http.ProblemDetail> notFound() {
                return org.springframework.http.ResponseEntity.status(404).body(org.springframework.http.ProblemDetail
                        .forStatusAndDetail(org.springframework.http.HttpStatus.NOT_FOUND, "tracked resource not found"));
            }
        }

        @Bean
        NotFoundAdvice notFoundAdvice() {
            return new NotFoundAdvice();
        }

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

    // PublicDonationMapper lo necesita (ficha R-6, G-1); lo implementa app, que este test no levanta
    @MockBean
    private com.traceability.contracts.campaign.CampaignPublicSummaryPort campaignSummaries;
    // PublicDonationMapper lo necesita (ficha R-6, G-2); lo implementa core, que este test no levanta entero
    @MockBean
    private com.traceability.core.application.port.out.FundClearingDatesReadPort clearingDates;
    // AssetHistoryMapper lo necesita (ficha R-6, G-3 y Enmienda 1, E1); lo implementa app, que este test no levanta
    @MockBean
    private com.traceability.contracts.AssetAnchorStatusPort assetAnchors;
    // Tarea 4 (ADR-021-D Enm. 2): lo implementa app; aquí es un doble
    @MockBean
    private com.traceability.core.application.port.out.PublishedEvidencePreviewPort previews;

    private static final String FUND = "fund-prev";
    private static final String ASSET = "asset-prev-1";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1, 2, 3};

    @Autowired
    private AssetRefService assetRefService;

    @BeforeEach
    void setUp() {
        donationReadRepository.deleteAll();
        Mockito.reset(previews, assetAuthorizationPort);
        donationReadRepository.save(DonationProjectionDocument.builder()
                .projectionId(FUND).currency("USD").campaignRef("CAM-1")
                .financialSnapshot(DonationProjectionDocument.FinancialSnapshot.builder()
                        .originalAmount(1000).clearedAmount(1000).pendingAllocationAmount(0).refundedAmount(0).build())
                .status("CLEARED")
                .logistics(List.of(DonationProjectionDocument.LogisticsProjection.builder().assetId(ASSET)
                        .lifecycleStatus("DELIVERED").assetType("Food").unitOfMeasure("kg").quantity(java.math.BigDecimal.TEN)
                        .currentLocation("LOC-1").currentCustodian("DELIVERED").build()))
                .build());
        Mockito.when(assetAuthorizationPort.assetBelongsToFund(ASSET, FUND)).thenReturn(true);
    }

    private String code() {
        return trackingCodeService.generate(FUND, Instant.now().plusSeconds(3600));
    }

    private String path() {
        return "/api/v1/donations/tracking/assets/" + assetRefService.computeRef(ASSET) + "/evidence-preview";
    }

    // test 1: la copia publicada, con el trackingCode correcto
    @Test
    void aPublishedCopy_isReturnedAsPng_privateAndNoStore() throws Exception {
        Mockito.when(previews.findPublishedPreview(ASSET)).thenReturn(Optional.of(PNG));

        MvcResult result = mockMvc.perform(get(path()).header("Authorization", "Bearer " + code()))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("no-store"), org.hamcrest.Matchers.containsString("private"))))
                .andReturn();

        org.junit.jupiter.api.Assertions.assertArrayEquals(PNG, result.getResponse().getContentAsByteArray());
    }

    // test 2: sin copia publicada → 404 (el mismo cuerpo siempre; aquí el puerto ya colapsa los cuatro casos en «vacío»)
    @Test
    void withoutAPublishedCopy_isA404_withAFixedBody() throws Exception {
        Mockito.when(previews.findPublishedPreview(ASSET)).thenReturn(Optional.empty());

        MvcResult first = mockMvc.perform(get(path()).header("Authorization", "Bearer " + code()))
                .andExpect(status().isNotFound()).andReturn();
        MvcResult second = mockMvc.perform(get(path()).header("Authorization", "Bearer " + code()))
                .andExpect(status().isNotFound()).andReturn();

        org.junit.jupiter.api.Assertions.assertArrayEquals(first.getResponse().getContentAsByteArray(),
                second.getResponse().getContentAsByteArray());
        org.junit.jupiter.api.Assertions.assertFalse(first.getResponse().getContentAsString().toLowerCase().contains("publish"));
        org.junit.jupiter.api.Assertions.assertFalse(first.getResponse().getContentAsString().toLowerCase().contains("remov"));
    }

    // test 2 (negativa): un código que no cubre el activo → el mismo 401 que el historial, nunca 404, y el puerto ni se toca
    @Test
    void aCodeThatDoesNotCoverTheAsset_isTheUniform401_neverA404_andTheStoreIsNotTouched() throws Exception {
        String other = trackingCodeService.generate("otro-fondo", Instant.now().plusSeconds(3600));
        Mockito.when(previews.findPublishedPreview(Mockito.anyString())).thenReturn(Optional.of(PNG));

        // el fondo del código no tiene proyección → mismo 401
        mockMvc.perform(get(path()).header("Authorization", "Bearer " + other))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.detail").value("Invalid or missing token"));
        // el activo no es del fondo
        Mockito.when(assetAuthorizationPort.assetBelongsToFund(ASSET, FUND)).thenReturn(false);
        mockMvc.perform(get(path()).header("Authorization", "Bearer " + code()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.detail").value("Invalid or missing token"));
        // una referencia que no se resuelve
        mockMvc.perform(get("/api/v1/donations/tracking/assets/inventada/evidence-preview").header("Authorization", "Bearer " + code()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.detail").value("Invalid or missing token"));

        Mockito.verify(previews, Mockito.never()).findPublishedPreview(Mockito.anyString());
        Mockito.verify(previews, Mockito.never()).hasPublishedPreview(Mockito.anyString());
    }

    @Test
    void withoutAnInvalidOrMissingCode_isA401_andTheStoreIsNotTouched() throws Exception {
        mockMvc.perform(get(path())).andExpect(status().isUnauthorized());
        mockMvc.perform(get(path()).header("Authorization", "Bearer basura")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(path()).header("Authorization", code())).andExpect(status().isUnauthorized()); // sin «Bearer »
        Mockito.verify(previews, Mockito.never()).findPublishedPreview(Mockito.anyString());
    }

    // test 6 (E6): el código en la consulta jamás sustituye a la cabecera, y con cabecera se ignora
    @Test
    void aCodeInTheQuery_neverReplacesTheHeader_andIsIgnoredWhenTheHeaderIsThere() throws Exception {
        Mockito.when(previews.findPublishedPreview(ASSET)).thenReturn(Optional.of(PNG));
        String valid = code();

        for (String param : List.of("code", "trackingCode", "token", "access_token")) {
            mockMvc.perform(get(path()).queryParam(param, valid)).andExpect(status().isUnauthorized());
        }
        Mockito.verify(previews, Mockito.never()).findPublishedPreview(Mockito.anyString());

        MvcResult ok = mockMvc.perform(get(path()).queryParam("code", "otro-codigo").queryParam("trackingCode", "x")
                        .header("Authorization", "Bearer " + valid))
                .andExpect(status().isOk()).andReturn();
        // aserción negativa: ni el cuerpo ni las cabeceras llevan el código
        org.junit.jupiter.api.Assertions.assertFalse(new String(ok.getResponse().getContentAsByteArray(),
                java.nio.charset.StandardCharsets.ISO_8859_1).contains(valid));
        for (String name : ok.getResponse().getHeaderNames()) {
            org.junit.jupiter.api.Assertions.assertFalse(String.valueOf(ok.getResponse().getHeaders(name)).contains(valid), name);
        }
    }

    // el historial anuncia la URL solo si el puerto dice que la copia está publicada
    @Test
    void theHistory_announcesTheUrl_onlyWhenThePortSaysPublished() throws Exception {
        String ref = assetRefService.computeRef(ASSET);
        String evidence = "sha256:" + "a".repeat(64);
        assetHistoryProjectionRepository.deleteAll();
        assetHistoryProjectionRepository.save(AssetHistoryProjectionDocument.builder().assetId(ASSET).transitions(List.of(
                AssetHistoryProjectionDocument.AssetTransition.builder().eventType("ASSET_DELIVERED")
                        .timestamp("2026-09-06T10:00:00Z").location("LOC-1").custodian("c").status("DELIVERED").sequence(1L).build()))
                .build());
        Mockito.when(assetAnchors.anchorBySequence(ASSET)).thenReturn(java.util.Map.of(1L,
                new com.traceability.contracts.AssetAnchorStatusPort.StepAnchor(
                        com.traceability.contracts.TrackingAnchorStatus.PENDING, "h", null, evidence)));

        Mockito.when(previews.hasPublishedPreview(ASSET)).thenReturn(true);
        mockMvc.perform(get("/api/v1/donations/tracking/assets/" + ref + "/history").header("Authorization", "Bearer " + code()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.history[0].evidencePreviewUrl")
                        .value("/api/v1/donations/tracking/assets/" + ref + "/evidence-preview"))
                .andExpect(jsonPath("$.history[0].evidenceRef").value(evidence));

        Mockito.when(previews.hasPublishedPreview(ASSET)).thenReturn(false);
        mockMvc.perform(get("/api/v1/donations/tracking/assets/" + ref + "/history").header("Authorization", "Bearer " + code()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.history[0].evidencePreviewUrl").doesNotExist())
                .andExpect(jsonPath("$.history[0].evidenceRef").value(evidence));
    }
}
