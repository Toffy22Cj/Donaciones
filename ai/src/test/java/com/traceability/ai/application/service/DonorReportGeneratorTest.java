package com.traceability.ai.application.service;

import com.traceability.ai.application.config.AiNarrativeProperties;
import com.traceability.ai.application.port.out.DonorReportRepositoryPort;
import com.traceability.ai.application.port.out.GroundingValidator;
import com.traceability.ai.application.port.out.LlmClientPort;
import com.traceability.ai.domain.exception.NarrativeGenerationTimeoutException;
import com.traceability.ai.domain.narrative.DonorReportDTO;
import com.traceability.ai.domain.narrative.LlmNarrativeResponse;
import com.traceability.ai.domain.narrative.NarrativeConstants;
import com.traceability.ai.domain.narrative.NarrativeSource;
import com.traceability.contracts.AuditFactsDTO;
import com.traceability.contracts.AuditFactsPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DonorReportGeneratorTest {

    private AuditFactsPort auditFactsPort;
    private LlmClientPort llmClientPort;
    private GroundingValidator groundingValidator;
    private NarrativePromptSanitizer sanitizer;
    private FallbackNarrativeTemplateService fallbackService;
    private DonorReportRepositoryPort repositoryPort;
    private NarrativeCacheCoordinator cacheCoordinator;
    private AiNarrativeProperties properties;

    private DonorReportGenerator generator;

    @BeforeEach
    void setup() {
        auditFactsPort = mock(AuditFactsPort.class);
        llmClientPort = mock(LlmClientPort.class);
        groundingValidator = mock(GroundingValidator.class);
        sanitizer = new NarrativePromptSanitizer(100);
        properties = new AiNarrativeProperties();
        properties.setFallbackRetryInterval(Duration.ofMinutes(15));
        properties.setPromptTemplateVersion("v1");
        properties.setModelIdentifier("gpt-4");
        
        fallbackService = new FallbackNarrativeTemplateService(properties);
        repositoryPort = mock(DonorReportRepositoryPort.class);
        cacheCoordinator = new NarrativeCacheCoordinator();

        generator = new DonorReportGenerator(auditFactsPort, llmClientPort, groundingValidator, sanitizer, 
                fallbackService, repositoryPort, cacheCoordinator, properties);
    }

    @Test
    void testHappyPath() {
        AuditFactsDTO facts = new AuditFactsDTO("fund-1", 1L, List.of(), List.of(), Instant.now());
        when(auditFactsPort.getAuditFacts("fund-1")).thenReturn(Optional.of(facts));
        when(repositoryPort.findByLogicalKey("fund-1", 1L, NarrativeConstants.SNAPSHOT_HASH_V1, "v1", "gpt-4"))
                .thenReturn(Optional.empty());

        LlmNarrativeResponse llmResp = new LlmNarrativeResponse("Great success!", Collections.emptyList());
        when(llmClientPort.generateNarrative(facts, "v1")).thenReturn(llmResp);
        when(groundingValidator.validate(llmResp, facts)).thenReturn(true);
        when(repositoryPort.save(eq("fund-1"), any(DonorReportDTO.class))).thenAnswer(i -> i.getArgument(1));

        DonorReportDTO result = generator.generate("fund-1");

        assertEquals("Great success!", result.narrativeText());
        assertEquals(NarrativeSource.LLM_GENERATED, result.source());
        verify(repositoryPort, times(1)).save(eq("fund-1"), any(DonorReportDTO.class));
    }

    @Test
    void testGroundingFailure_GeneratesFallback() {
        AuditFactsDTO facts = new AuditFactsDTO("fund-2", 2L, List.of(), List.of(), Instant.now());
        when(auditFactsPort.getAuditFacts("fund-2")).thenReturn(Optional.of(facts));
        when(repositoryPort.findByLogicalKey(anyString(), anyLong(), anyString(), anyString(), anyString())).thenReturn(Optional.empty());

        LlmNarrativeResponse llmResp = new LlmNarrativeResponse("Hallucinated text", Collections.emptyList());
        when(llmClientPort.generateNarrative(facts, "v1")).thenReturn(llmResp);
        when(groundingValidator.validate(llmResp, facts)).thenReturn(false); // Grounding fails
        when(repositoryPort.save(eq("fund-2"), any(DonorReportDTO.class))).thenAnswer(i -> i.getArgument(1));

        DonorReportDTO result = generator.generate("fund-2");

        assertEquals(NarrativeSource.FALLBACK_TEMPLATE, result.source());
        assertTrue(result.narrativeText().contains("temporarily unavailable"));
        assertNotNull(result.nextRetryAt());
    }

    @Test
    void testFallbackWithinTTL_ReturnsCached() {
        AuditFactsDTO facts = new AuditFactsDTO("fund-3", 3L, List.of(), List.of(), Instant.now());
        when(auditFactsPort.getAuditFacts("fund-3")).thenReturn(Optional.of(facts));

        DonorReportDTO cachedFallback = new DonorReportDTO(
            "Fallback", NarrativeSource.FALLBACK_TEMPLATE, "FALLBACK", "v1", 
            NarrativeConstants.SNAPSHOT_HASH_V1, 3L, Instant.now(), Instant.now().plus(Duration.ofMinutes(10))
        );

        when(repositoryPort.findByLogicalKey("fund-3", 3L, NarrativeConstants.SNAPSHOT_HASH_V1, "v1", "gpt-4"))
                .thenReturn(Optional.of(cachedFallback));

        DonorReportDTO result = generator.generate("fund-3");

        assertEquals("Fallback", result.narrativeText());
        verify(llmClientPort, never()).generateNarrative(any(), anyString()); // LLM was not called
    }
    
    @Test
    void testFallbackAfterTTL_CallsLLM() {
        AuditFactsDTO facts = new AuditFactsDTO("fund-3", 3L, List.of(), List.of(), Instant.now());
        when(auditFactsPort.getAuditFacts("fund-3")).thenReturn(Optional.of(facts));

        // Expired fallback
        DonorReportDTO cachedFallback = new DonorReportDTO(
            "Fallback", NarrativeSource.FALLBACK_TEMPLATE, "FALLBACK", "v1", 
            NarrativeConstants.SNAPSHOT_HASH_V1, 3L, Instant.now(), Instant.now().minus(Duration.ofMinutes(10))
        );

        when(repositoryPort.findByLogicalKey("fund-3", 3L, NarrativeConstants.SNAPSHOT_HASH_V1, "v1", "gpt-4"))
                .thenReturn(Optional.of(cachedFallback));

        LlmNarrativeResponse llmResp = new LlmNarrativeResponse("New success!", Collections.emptyList());
        when(llmClientPort.generateNarrative(facts, "v1")).thenReturn(llmResp);
        when(groundingValidator.validate(llmResp, facts)).thenReturn(true);
        when(repositoryPort.save(eq("fund-3"), any(DonorReportDTO.class))).thenAnswer(i -> i.getArgument(1));

        DonorReportDTO result = generator.generate("fund-3");

        assertEquals("New success!", result.narrativeText());
        assertEquals(NarrativeSource.LLM_GENERATED, result.source());
        verify(llmClientPort, times(1)).generateNarrative(any(), anyString());
    }

    @Test
    void testSingleFlightConcurrency() throws InterruptedException {
        AuditFactsDTO facts = new AuditFactsDTO("fund-4", 4L, List.of(), List.of(), Instant.now());
        when(auditFactsPort.getAuditFacts("fund-4")).thenReturn(Optional.of(facts));
        when(repositoryPort.findByLogicalKey(anyString(), anyLong(), anyString(), anyString(), anyString())).thenReturn(Optional.empty());

        AtomicInteger llmCalls = new AtomicInteger(0);
        when(llmClientPort.generateNarrative(facts, "v1")).thenAnswer(inv -> {
            llmCalls.incrementAndGet();
            Thread.sleep(500); // Simulate network latency
            return new LlmNarrativeResponse("Concurrent success", Collections.emptyList());
        });
        when(groundingValidator.validate(any(), any())).thenReturn(true);
        when(repositoryPort.save(eq("fund-4"), any(DonorReportDTO.class))).thenAnswer(i -> i.getArgument(1));

        ExecutorService executor = Executors.newFixedThreadPool(5);
        CountDownLatch latch = new CountDownLatch(5);

        for (int i = 0; i < 5; i++) {
            executor.submit(() -> {
                try {
                    generator.generate("fund-4");
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();

        assertEquals(1, llmCalls.get()); // The LLM was called only once despite 5 concurrent requests!
    }

    @Test
    void testTimeout_GeneratesFallback() {
        AuditFactsDTO facts = new AuditFactsDTO("fund-timeout", 1L, List.of(), List.of(), Instant.now());
        when(auditFactsPort.getAuditFacts("fund-timeout")).thenReturn(Optional.of(facts));
        when(repositoryPort.findByLogicalKey(anyString(), anyLong(), anyString(), anyString(), anyString())).thenReturn(Optional.empty());

        when(llmClientPort.generateNarrative(facts, "v1")).thenThrow(new NarrativeGenerationTimeoutException("Timeout", null));
        when(repositoryPort.save(eq("fund-timeout"), any(DonorReportDTO.class))).thenAnswer(i -> i.getArgument(1));

        DonorReportDTO result = generator.generate("fund-timeout");

        assertEquals(NarrativeSource.FALLBACK_TEMPLATE, result.source());
        assertTrue(result.narrativeText().contains("temporarily unavailable"));
    }

    @Test
    void testProviderException_GeneratesFallback() {
        AuditFactsDTO facts = new AuditFactsDTO("fund-err", 1L, List.of(), List.of(), Instant.now());
        when(auditFactsPort.getAuditFacts("fund-err")).thenReturn(Optional.of(facts));
        when(repositoryPort.findByLogicalKey(anyString(), anyLong(), anyString(), anyString(), anyString())).thenReturn(Optional.empty());

        when(llmClientPort.generateNarrative(facts, "v1")).thenThrow(new com.traceability.ai.domain.exception.NarrativeProviderException("Provider error", null));
        when(repositoryPort.save(eq("fund-err"), any(DonorReportDTO.class))).thenAnswer(i -> i.getArgument(1));

        DonorReportDTO result = generator.generate("fund-err");

        assertEquals(NarrativeSource.FALLBACK_TEMPLATE, result.source());
        assertTrue(result.narrativeText().contains("temporarily unavailable"));
    }

    @Test
    void testPromptSanitization() {
        // En un futuro el sanitizer podría ocultar datos. Ahora lo validamos pasándole el objeto y viendo que se llame al LLM con el sanitizado.
        // Dado que sanitizer.sanitize() en este MVP devuelve el mismo objeto, usamos un mock para asegurar que pasa por ahí.
        NarrativePromptSanitizer mockSanitizer = mock(NarrativePromptSanitizer.class);
        DonorReportGenerator genWithMockSanitizer = new DonorReportGenerator(auditFactsPort, llmClientPort, groundingValidator, mockSanitizer, 
                fallbackService, repositoryPort, cacheCoordinator, properties);

        AuditFactsDTO rawFacts = new AuditFactsDTO("fund-san", 1L, List.of(), List.of(), Instant.now());
        AuditFactsDTO sanitizedFacts = new AuditFactsDTO("fund-san-safe", 1L, List.of(), List.of(), Instant.now());
        
        when(auditFactsPort.getAuditFacts("fund-san")).thenReturn(Optional.of(rawFacts));
        when(mockSanitizer.sanitize(rawFacts)).thenReturn(sanitizedFacts);
        when(repositoryPort.findByLogicalKey(anyString(), anyLong(), anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(llmClientPort.generateNarrative(sanitizedFacts, "v1")).thenReturn(new LlmNarrativeResponse("Safe", List.of()));
        when(groundingValidator.validate(any(), eq(sanitizedFacts))).thenReturn(true);
        when(repositoryPort.save(anyString(), any(DonorReportDTO.class))).thenAnswer(i -> i.getArgument(1));

        genWithMockSanitizer.generate("fund-san");
        
        verify(mockSanitizer).sanitize(rawFacts);
        verify(llmClientPort).generateNarrative(sanitizedFacts, "v1");
        verify(groundingValidator).validate(any(), eq(sanitizedFacts));
    }

    @Test
    void testCacheIsolationByPromptTemplateVersion() {
        AuditFactsDTO facts = new AuditFactsDTO("fund-5", 5L, List.of(), List.of(), Instant.now());
        when(auditFactsPort.getAuditFacts("fund-5")).thenReturn(Optional.of(facts));
        
        // Hay reporte cacheado para la v1
        DonorReportDTO cachedV1 = new DonorReportDTO(
            "V1 report", NarrativeSource.LLM_GENERATED, "gpt-4", "v1", 
            NarrativeConstants.SNAPSHOT_HASH_V1, 5L, Instant.now(), null
        );
        when(repositoryPort.findByLogicalKey("fund-5", 5L, NarrativeConstants.SNAPSHOT_HASH_V1, "v1", "gpt-4"))
                .thenReturn(Optional.of(cachedV1));
        
        // Pero properties está configurado para la v2!
        properties.setPromptTemplateVersion("v2");
        when(repositoryPort.findByLogicalKey("fund-5", 5L, NarrativeConstants.SNAPSHOT_HASH_V1, "v2", "gpt-4"))
                .thenReturn(Optional.empty()); // No hay cache para la v2
        
        LlmNarrativeResponse llmRespV2 = new LlmNarrativeResponse("V2 success!", Collections.emptyList());
        when(llmClientPort.generateNarrative(facts, "v2")).thenReturn(llmRespV2);
        when(groundingValidator.validate(llmRespV2, facts)).thenReturn(true);
        when(repositoryPort.save(eq("fund-5"), any(DonorReportDTO.class))).thenAnswer(i -> i.getArgument(1));
        
        DonorReportDTO result = generator.generate("fund-5");
        
        // Debe haber invocado al LLM para la v2
        assertEquals("V2 success!", result.narrativeText());
        assertEquals("v2", result.promptTemplateVersion());
        verify(llmClientPort, times(1)).generateNarrative(facts, "v2");
    }
}
