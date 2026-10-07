package com.traceability.ai.application.service;

import com.traceability.ai.application.config.AiNarrativeProperties;
import com.traceability.ai.application.port.out.CampaignLlmClientPort;
import com.traceability.ai.domain.narrative.CampaignLlmNarrativeResponse;
import com.traceability.ai.domain.narrative.CampaignNarrative;
import com.traceability.ai.domain.narrative.CampaignNarrativeFacts;
import com.traceability.contracts.CampaignAuditFactsDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * Narrativa pública de una convocatoria (ADR-040; plan B5, DD-34). Los hechos son efímeros: llegan en cada petición.
 * La narrativa se guarda en memoria por convocatoria con la clave {@code hash(hechos) + promptTemplateVersion +
 * modelIdentifier}: si los hechos no cambian no hay otra llamada al LLM; si cambian, se regenera. La generación es
 * asíncrona y de un solo vuelo por clave; mientras dura, la respuesta es {@code PENDING}.
 * <p>
 * Solo se publica texto que pasó {@link CampaignGroundingValidator}. Si no pasa, o el proveedor falla, la respuesta es
 * {@link CampaignNarrative#unavailable()}; un fallo del proveedor se reintenta tras {@code fallbackRetryInterval}, un
 * rechazo de grounding no (los mismos hechos darían el mismo riesgo).
 */
@Service
public class CampaignNarrativeGenerator {

    private static final Logger log = LoggerFactory.getLogger(CampaignNarrativeGenerator.class);

    private record Entry(String key, CompletableFuture<Outcome> future) {}

    private record Outcome(CampaignNarrative narrative, Instant retryAfter) {}

    private final CampaignLlmClientPort llm;
    private final CampaignGroundingValidator validator;
    private final AiNarrativeProperties properties;
    private final Clock clock;
    private final Executor executor;
    private final ConcurrentMap<String, Entry> byCampaign = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public CampaignNarrativeGenerator(CampaignLlmClientPort llm, CampaignGroundingValidator validator,
                                      AiNarrativeProperties properties) {
        this(llm, validator, properties, Clock.systemUTC(), ForkJoinPool.commonPool());
    }

    CampaignNarrativeGenerator(CampaignLlmClientPort llm, CampaignGroundingValidator validator,
                               AiNarrativeProperties properties, Clock clock, Executor executor) {
        this.llm = llm;
        this.validator = validator;
        this.properties = properties;
        this.clock = clock;
        this.executor = executor;
    }

    public CampaignNarrative narrativeOf(CampaignAuditFactsDTO dto) {
        CampaignNarrativeFacts facts = CampaignNarrativeFacts.of(dto);
        String key = sha256(facts.canonical()) + "|" + properties.getPromptTemplateVersion() + "|"
                + properties.getModelIdentifier();
        Entry entry = byCampaign.compute(dto.campaignRef(), (ref, current) ->
                current != null && current.key().equals(key) && !expired(current) ? current
                        : new Entry(key, CompletableFuture.supplyAsync(() -> generate(facts), executor)));
        CompletableFuture<Outcome> future = entry.future();
        return future.isDone() ? future.join().narrative() : CampaignNarrative.pending();
    }

    private boolean expired(Entry entry) {
        if (!entry.future().isDone()) {
            return false;
        }
        Instant retryAfter = entry.future().join().retryAfter();
        return retryAfter != null && !clock.instant().isBefore(retryAfter);
    }

    private Outcome generate(CampaignNarrativeFacts facts) {
        CampaignLlmNarrativeResponse response;
        try {
            response = llm.generateCampaignNarrative(facts, properties.getPromptTemplateVersion());
        } catch (RuntimeException e) {
            log.warn("Campaign narrative provider failed: {}", e.getClass().getSimpleName());
            return new Outcome(CampaignNarrative.unavailable(), clock.instant().plus(properties.getFallbackRetryInterval()));
        }
        if (!validator.validate(response, facts)) {
            log.warn("Campaign narrative rejected by grounding; not published");
            return new Outcome(CampaignNarrative.unavailable(), null);
        }
        return new Outcome(CampaignNarrative.available(response.narrativeText()), null);
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
