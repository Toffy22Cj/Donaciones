package com.traceability.ai.application.service;

import com.traceability.ai.application.config.AiNarrativeProperties;
import com.traceability.ai.application.port.out.CampaignLlmClientPort;
import com.traceability.ai.domain.exception.NarrativeProviderException;
import com.traceability.ai.domain.narrative.CampaignCitedFact;
import com.traceability.ai.domain.narrative.CampaignFactType;
import com.traceability.ai.domain.narrative.CampaignLlmNarrativeResponse;
import com.traceability.ai.domain.narrative.CampaignNarrative;
import com.traceability.ai.domain.narrative.CampaignNarrativeFacts;
import com.traceability.ai.domain.narrative.NarrativeSource;
import com.traceability.contracts.CampaignAuditFactsDTO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/** Plan B5 (DD-34): caché por hash de los hechos; solo se publica texto validado; fallo → "Narrativa no disponible". */
class CampaignNarrativeGeneratorTest {

    static final Instant T0 = Instant.parse("2027-01-10T10:00:00Z");

    static class SimulatedLlm implements CampaignLlmClientPort {
        final List<CampaignNarrativeFacts> calls = new ArrayList<>();
        Function<CampaignNarrativeFacts, CampaignLlmNarrativeResponse> answer;

        @Override
        public CampaignLlmNarrativeResponse generateCampaignNarrative(CampaignNarrativeFacts facts, String version) {
            calls.add(facts);
            return answer.apply(facts);
        }
    }

    private final SimulatedLlm llm = new SimulatedLlm();
    private final AiNarrativeProperties properties = new AiNarrativeProperties();
    private final List<Runnable> queued = new ArrayList<>();
    private final Executor deferred = queued::add;

    private CampaignNarrativeGenerator generator(Executor executor, java.util.function.Supplier<Instant> now) {
        Clock c = new Clock() {
            public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(java.time.ZoneId zone) { return this; }
            public Instant instant() { return now.get(); }
        };
        return new CampaignNarrativeGenerator(llm, new CampaignGroundingValidator(), properties, c, executor);
    }

    static CampaignAuditFactsDTO facts(String units, long recipients) {
        return new CampaignAuditFactsDTO("CAMP-1", "OPEN", "ORG-1", new BigDecimal("100000"), "SOFT_TARGET",
                new BigDecimal("60000"), new BigDecimal(units), recipients, "COP", T0, T0, T0);
    }

    static CampaignLlmNarrativeResponse grounded(CampaignNarrativeFacts f) {
        String units = CampaignNarrativeFacts.plain(f.unitsDelivered());
        return new CampaignLlmNarrativeResponse("Se entregaron " + units + " unidades a " + f.distinctRecipients()
                + " receptores distintos.", List.of(new CampaignCitedFact(CampaignFactType.UNITS_DELIVERED, units),
                new CampaignCitedFact(CampaignFactType.DISTINCT_RECIPIENTS, Long.toString(f.distinctRecipients()))));
    }

    @Test
    void pendingWhileGenerating_thenAvailable_withTheValidatedText() {
        llm.answer = CampaignNarrativeGeneratorTest::grounded;
        CampaignNarrativeGenerator generator = generator(deferred, () -> T0);

        assertThat(generator.narrativeOf(facts("15", 2)).status()).isEqualTo(CampaignNarrative.Status.PENDING);
        queued.forEach(Runnable::run);

        CampaignNarrative narrative = generator.narrativeOf(facts("15", 2));
        assertThat(narrative.status()).isEqualTo(CampaignNarrative.Status.AVAILABLE);
        assertThat(narrative.content()).isEqualTo("Se entregaron 15 unidades a 2 receptores distintos.");
        assertThat(narrative.source()).isEqualTo(NarrativeSource.LLM_GENERATED);
    }

    @Test
    void sameFacts_doNotCallTheLlmAgain_andNewFacts_regenerate() {
        llm.answer = CampaignNarrativeGeneratorTest::grounded;
        CampaignNarrativeGenerator generator = generator(Runnable::run, () -> T0);

        generator.narrativeOf(facts("15", 2));
        generator.narrativeOf(facts("15.0000", 2));
        assertThat(llm.calls).as("la misma cifra con otra escala es el mismo hecho").hasSize(1);

        CampaignNarrative updated = generator.narrativeOf(facts("20", 3));
        assertThat(llm.calls).hasSize(2);
        assertThat(updated.content()).contains("20 unidades").contains("3 receptores distintos");
    }

    @Test
    void theLlmReceivesOnlyTheDeterministicFacts() {
        llm.answer = CampaignNarrativeGeneratorTest::grounded;
        generator(Runnable::run, () -> T0).narrativeOf(facts("15", 2));

        CampaignNarrativeFacts sent = llm.calls.get(0);
        assertThat(sent).isEqualTo(new CampaignNarrativeFacts("OPEN", "COP", new BigDecimal("100000"), "SOFT_TARGET",
                new BigDecimal("60000"), new BigDecimal("15"), 2));
        assertThat(CampaignNarrativePrompt.render(sent, "{}")).doesNotContain("CAMP-1").doesNotContain("ORG-1");
    }

    @Test
    void groundingFailure_isNeverPublished() {
        llm.answer = f -> new CampaignLlmNarrativeResponse("Llegamos a 500 familias.",
                List.of(new CampaignCitedFact(CampaignFactType.DISTINCT_RECIPIENTS, "2")));

        CampaignNarrative narrative = generator(Runnable::run, () -> T0).narrativeOf(facts("15", 2));

        assertThat(narrative.status()).isEqualTo(CampaignNarrative.Status.UNAVAILABLE);
        assertThat(narrative.content()).isEqualTo("Narrativa no disponible");
        assertThat(narrative.source()).isNull();
    }

    @Test
    void providerFailure_isUnavailable_andIsRetriedAfterTheInterval() {
        properties.setFallbackRetryInterval(Duration.ofMinutes(15));
        Instant[] now = {T0};
        llm.answer = f -> { throw new NarrativeProviderException("down", null); };
        CampaignNarrativeGenerator generator = generator(Runnable::run, () -> now[0]);

        assertThat(generator.narrativeOf(facts("15", 2)).content()).isEqualTo("Narrativa no disponible");
        generator.narrativeOf(facts("15", 2));
        assertThat(llm.calls).as("antes del intervalo no se reintenta").hasSize(1);

        now[0] = T0.plus(Duration.ofMinutes(15));
        llm.answer = CampaignNarrativeGeneratorTest::grounded;
        assertThat(generator.narrativeOf(facts("15", 2)).status()).isEqualTo(CampaignNarrative.Status.AVAILABLE);
        assertThat(llm.calls).hasSize(2);
    }

    @Test
    void groundingRejection_isNotRetriedForTheSameFacts() {
        llm.answer = f -> new CampaignLlmNarrativeResponse("Sin citas.", List.of());
        Instant[] now = {T0};
        CampaignNarrativeGenerator generator = generator(Runnable::run, () -> now[0]);

        generator.narrativeOf(facts("15", 2));
        now[0] = T0.plus(Duration.ofDays(1));
        generator.narrativeOf(facts("15", 2));

        assertThat(llm.calls).hasSize(1);
    }
}
