package com.traceability.ai.application.service;

import com.traceability.ai.application.config.AiNarrativeProperties;
import com.traceability.ai.application.port.out.DonorReportRepositoryPort;
import com.traceability.ai.application.port.out.GroundingValidator;
import com.traceability.ai.application.port.out.LlmClientPort;
import com.traceability.ai.domain.exception.NarrativeGroundingFailedException;
import com.traceability.ai.domain.narrative.CacheKey;
import com.traceability.ai.domain.narrative.DonorReportDTO;
import com.traceability.ai.domain.narrative.LlmNarrativeResponse;
import com.traceability.ai.domain.narrative.NarrativeConstants;
import com.traceability.ai.domain.narrative.NarrativeSource;
import com.traceability.contracts.AuditFactsDTO;
import com.traceability.contracts.AuditFactsPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Service
public class DonorReportGenerator {
    private static final Logger log = LoggerFactory.getLogger(DonorReportGenerator.class);

    private final AuditFactsPort auditFactsPort;
    private final LlmClientPort llmClientPort;
    private final GroundingValidator groundingValidator;
    private final NarrativePromptSanitizer sanitizer;
    private final FallbackNarrativeTemplateService fallbackService;
    private final DonorReportRepositoryPort repositoryPort;
    private final NarrativeCacheCoordinator cacheCoordinator;
    private final AiNarrativeProperties properties;

    public DonorReportGenerator(AuditFactsPort auditFactsPort,
                                LlmClientPort llmClientPort,
                                GroundingValidator groundingValidator,
                                NarrativePromptSanitizer sanitizer,
                                FallbackNarrativeTemplateService fallbackService,
                                DonorReportRepositoryPort repositoryPort,
                                NarrativeCacheCoordinator cacheCoordinator,
                                AiNarrativeProperties properties) {
        this.auditFactsPort = auditFactsPort;
        this.llmClientPort = llmClientPort;
        this.groundingValidator = groundingValidator;
        this.sanitizer = sanitizer;
        this.fallbackService = fallbackService;
        this.repositoryPort = repositoryPort;
        this.cacheCoordinator = cacheCoordinator;
        this.properties = properties;
    }

    public DonorReportDTO generate(String donationId) {
        AuditFactsDTO facts = auditFactsPort.getAuditFacts(donationId)
                .orElseThrow(() -> new IllegalArgumentException("Facts not found for donationId: " + donationId));

        CacheKey cacheKey = new CacheKey(
                donationId,
                facts.auditFactsSequence(),
                NarrativeConstants.SNAPSHOT_HASH_V1,
                properties.getPromptTemplateVersion(),
                properties.getModelIdentifier()
        );

        Optional<DonorReportDTO> existing = repositoryPort.findByLogicalKey(
                cacheKey.donationId(),
                cacheKey.auditFactsSequence(),
                cacheKey.sourceFactsHash(),
                cacheKey.promptTemplateVersion(),
                cacheKey.modelIdentifier()
        );

        if (existing.isPresent()) {
            DonorReportDTO cached = existing.get();
            if (cached.source() == NarrativeSource.FALLBACK_TEMPLATE) {
                if (cached.nextRetryAt() != null && cached.nextRetryAt().isAfter(Instant.now())) {
                    log.info("Returning cached fallback for donation {}", donationId);
                    return cached;
                }
                log.info("Fallback TTL expired for donation {}, re-attempting generation", donationId);
            } else {
                return cached;
            }
        }

        return cacheCoordinator.getOrCompute(cacheKey, () -> CompletableFuture.supplyAsync(() -> doGenerate(facts, cacheKey))).join();
    }

    private DonorReportDTO doGenerate(AuditFactsDTO facts, CacheKey cacheKey) {
        AuditFactsDTO sanitized = sanitizer.sanitize(facts);
        try {
            LlmNarrativeResponse response = llmClientPort.generateNarrative(sanitized, cacheKey.promptTemplateVersion());
            if (!groundingValidator.validate(response, sanitized)) {
                throw new NarrativeGroundingFailedException("Grounding validation failed for donation " + cacheKey.donationId());
            }
            DonorReportDTO report = new DonorReportDTO(
                    response.narrativeText(),
                    NarrativeSource.LLM_GENERATED,
                    cacheKey.modelIdentifier(),
                    cacheKey.promptTemplateVersion(),
                    cacheKey.sourceFactsHash(),
                    cacheKey.auditFactsSequence(),
                    Instant.now(),
                    null
            );
            return repositoryPort.save(cacheKey.donationId(), report);
        } catch (Exception e) {
            log.error("Generation failed for {}, persisting fallback", cacheKey.donationId(), e);
            DonorReportDTO fallback = fallbackService.createFallback(facts, cacheKey.sourceFactsHash());
            return repositoryPort.save(cacheKey.donationId(), fallback);
        }
    }
}
