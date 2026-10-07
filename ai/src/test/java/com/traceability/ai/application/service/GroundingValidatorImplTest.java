package com.traceability.ai.application.service;

import com.traceability.ai.application.config.AiNarrativeProperties;
import com.traceability.ai.application.port.out.DonorReportRepositoryPort;
import com.traceability.ai.application.port.out.LlmClientPort;
import com.traceability.ai.domain.narrative.CitedFact;
import com.traceability.ai.domain.narrative.DonorReportDTO;
import com.traceability.ai.domain.narrative.FactType;
import com.traceability.ai.domain.narrative.LlmNarrativeResponse;
import com.traceability.ai.domain.narrative.NarrativeSource;
import com.traceability.contracts.AuditFactsDTO;
import com.traceability.contracts.AuditFactsPort;
import com.traceability.contracts.TransitionFactDTO;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** H-P12-1 (Carlos, 2026-10-07): una narrativa individual sin ninguna cita no está fundamentada y va al fallback. */
class GroundingValidatorImplTest {

    static final Instant T0 = Instant.parse("2027-01-10T10:00:00Z");
    static final AuditFactsDTO DELIVERED = new AuditFactsDTO("fund-1", 3L,
            List.of(new TransitionFactDTO("asset-1", "RECEIVED", "DELIVERED", T0, T0.plusSeconds(60), 60, null, false)),
            List.of(), T0);

    private final GroundingValidatorImpl validator = new GroundingValidatorImpl();

    @Test
    void aNarrativeWithoutCitations_isNotGrounded() {
        assertThat(validator.validate(new LlmNarrativeResponse("Tu donación ya ayudó a muchas personas.", List.of()),
                DELIVERED)).isFalse();
    }

    @Test
    void aNarrativeCitingAnExistingFact_isGrounded() {
        assertThat(validator.validate(new LlmNarrativeResponse("Los bienes se entregaron.",
                List.of(new CitedFact(FactType.LIFECYCLE_STATUS, "DELIVERED"))), DELIVERED)).isTrue();
    }

    @Test
    void nullResponsesOrCitations_areNotGrounded() {
        assertThat(validator.validate(null, DELIVERED)).isFalse();
        assertThat(validator.validate(new LlmNarrativeResponse("x", null), DELIVERED)).isFalse();
    }

    @Test
    void theGenerator_publishesTheFallback_whenTheLlmCitesNothing() {
        AuditFactsPort facts = mock(AuditFactsPort.class);
        LlmClientPort llm = mock(LlmClientPort.class);
        DonorReportRepositoryPort repository = mock(DonorReportRepositoryPort.class);
        AiNarrativeProperties properties = new AiNarrativeProperties();
        when(facts.getAuditFacts("fund-1")).thenReturn(Optional.of(DELIVERED));
        when(repository.findByLogicalKey(anyString(), anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(repository.save(eq("fund-1"), any(DonorReportDTO.class))).thenAnswer(i -> i.getArgument(1));
        when(llm.generateNarrative(any(), anyString()))
                .thenReturn(new LlmNarrativeResponse("Tu donación cambió vidas.", List.of()));
        DonorReportGenerator generator = new DonorReportGenerator(facts, llm, validator, new NarrativePromptSanitizer(100),
                new FallbackNarrativeTemplateService(properties), repository, new NarrativeCacheCoordinator(), properties);

        DonorReportDTO report = generator.generate("fund-1");

        assertThat(report.source()).isEqualTo(NarrativeSource.FALLBACK_TEMPLATE);
        assertThat(report.narrativeText()).doesNotContain("cambió vidas");
    }
}
