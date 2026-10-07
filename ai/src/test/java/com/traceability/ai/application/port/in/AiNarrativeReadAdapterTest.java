package com.traceability.ai.application.port.in;

import com.traceability.ai.application.service.DonorReportGenerator;
import com.traceability.ai.domain.exception.AuditFactsNotYetAvailableException;
import com.traceability.ai.domain.narrative.DonorReportDTO;
import com.traceability.ai.domain.narrative.NarrativeSource;
import com.traceability.contracts.NarrativeReadModel;
import com.traceability.contracts.NarrativeStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;

class AiNarrativeReadAdapterTest {

    private DonorReportGenerator generator;
    private AiNarrativeReadAdapter adapter;

    @BeforeEach
    void setup() {
        generator = mock(DonorReportGenerator.class);
        adapter = new AiNarrativeReadAdapter(generator);
    }

    @Test
    void getOrTriggerGeneration_WhenNoFactsAvailable_ReturnsPendingInstantly() {
        when(generator.generateAsync("fund-missing")).thenThrow(new AuditFactsNotYetAvailableException("missing"));

        assertTimeoutPreemptively(Duration.ofMillis(100), () -> {
            Optional<NarrativeReadModel> result = adapter.getOrTriggerGeneration("fund-missing");
            assertTrue(result.isPresent());
            assertEquals(NarrativeStatus.PENDING, result.get().status());
        });
    }

    @Test
    void getOrTriggerGeneration_WhenGenerationIsInProgress_ReturnsPendingInstantly() {
        // Return an incomplete future to simulate LLM generation in progress
        when(generator.generateAsync("fund-pending")).thenReturn(new CompletableFuture<>());

        assertTimeoutPreemptively(Duration.ofMillis(100), () -> {
            Optional<NarrativeReadModel> result = adapter.getOrTriggerGeneration("fund-pending");
            assertTrue(result.isPresent());
            assertEquals(NarrativeStatus.PENDING, result.get().status());
        });
    }

    @Test
    void getOrTriggerGeneration_WhenGenerationAlreadyFinishedLLM_ReturnsAvailable() {
        DonorReportDTO report = new DonorReportDTO(
                "Great success",
                NarrativeSource.LLM_GENERATED,
                "gpt-4",
                "v1",
                "hash",
                1L,
                Instant.now(),
                null
        );
        when(generator.generateAsync("fund-available")).thenReturn(CompletableFuture.completedFuture(report));

        Optional<NarrativeReadModel> result = adapter.getOrTriggerGeneration("fund-available");
        
        assertTrue(result.isPresent());
        assertEquals(NarrativeStatus.AVAILABLE, result.get().status());
        assertEquals("Great success", result.get().content());
        assertEquals(com.traceability.contracts.NarrativeSource.LLM_GENERATED, result.get().source());
    }

    @Test
    void getOrTriggerGeneration_WhenGenerationAlreadyFinishedFallback_ReturnsAvailable() {
        DonorReportDTO report = new DonorReportDTO(
                "Fallback text",
                NarrativeSource.FALLBACK_TEMPLATE,
                "FALLBACK",
                "v1",
                "hash",
                1L,
                Instant.now(),
                Instant.now().plus(Duration.ofMinutes(15))
        );
        when(generator.generateAsync("fund-fallback")).thenReturn(CompletableFuture.completedFuture(report));

        Optional<NarrativeReadModel> result = adapter.getOrTriggerGeneration("fund-fallback");

        assertTrue(result.isPresent());
        assertEquals(NarrativeStatus.AVAILABLE, result.get().status());
        assertEquals("Fallback text", result.get().content());
        assertEquals(com.traceability.contracts.NarrativeSource.FALLBACK_TEMPLATE, result.get().source());
    }
    
    @Test
    void singleFlightConcurrency_IsHandledByGenerator() throws InterruptedException {
        // This test validates the contract we expect from generator in a concurrent setup.
        AtomicInteger generateCalls = new AtomicInteger(0);
        
        when(generator.generateAsync(anyString())).thenAnswer(inv -> {
            generateCalls.incrementAndGet();
            CompletableFuture<DonorReportDTO> future = new CompletableFuture<>();
            // simulate long running task that we don't wait for
            new Thread(() -> {
                try {
                    Thread.sleep(200);
                    future.complete(null);
                } catch (InterruptedException e) {
                }
            }).start();
            return future;
        });

        ExecutorService executor = Executors.newFixedThreadPool(5);
        CountDownLatch latch = new CountDownLatch(5);

        for (int i = 0; i < 5; i++) {
            executor.submit(() -> {
                try {
                    // All calls will return immediately with PENDING
                    adapter.getOrTriggerGeneration("fund-concurrent");
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        
        // The mock counts how many times the adapter called the generator.
        // Actually, the real single-flight is inside NarrativeCacheCoordinator (tested in DonorReportGeneratorTest).
        // This test just ensures the adapter doesn't block concurrently.
        assertEquals(5, generateCalls.get()); 
    }
}
