package com.traceability.ai.application.service;

import com.traceability.ai.domain.narrative.CacheKey;
import com.traceability.ai.domain.narrative.DonorReportDTO;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@Service
public class NarrativeCacheCoordinator {
    private final ConcurrentHashMap<CacheKey, CompletableFuture<DonorReportDTO>> futures = new ConcurrentHashMap<>();

    public CompletableFuture<DonorReportDTO> getOrCompute(CacheKey key, Supplier<CompletableFuture<DonorReportDTO>> computation) {
        return futures.computeIfAbsent(key, k -> {
            CompletableFuture<DonorReportDTO> future = computation.get();
            // Single-flight deduplication: remove from map when done
            future.whenComplete((res, ex) -> futures.remove(k));
            return future;
        });
    }
}
