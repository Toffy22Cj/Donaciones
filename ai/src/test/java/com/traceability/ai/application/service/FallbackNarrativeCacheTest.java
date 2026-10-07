package com.traceability.ai.application.service;

import com.traceability.ai.application.config.AiNarrativeProperties;
import com.traceability.ai.application.port.out.DonorReportRepositoryPort;
import com.traceability.ai.application.port.out.LlmClientPort;
import com.traceability.ai.domain.exception.NarrativeProviderException;
import com.traceability.ai.domain.narrative.DonorReportDTO;
import com.traceability.ai.domain.narrative.NarrativeSource;
import com.traceability.contracts.AuditFactsDTO;
import com.traceability.contracts.AuditFactsPort;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Hallazgo de la demo local (2026-10-07): sin proveedor de LLM, el fallback se guardaba con
 * {@code modelIdentifier = "FALLBACK"} pero se buscaba con el modelo configurado; nunca se encontraba, cada consulta
 * relanzaba la generación (una llamada al proveedor y un documento nuevo) y la narrativa no salía de {@code PENDING}.
 * El fallback vigente debe servirse sin volver a llamar al proveedor hasta {@code nextRetryAt}.
 */
class FallbackNarrativeCacheTest {

    /** Repositorio en memoria con la misma clave lógica que el adaptador de Mongo. */
    static class InMemoryReports implements DonorReportRepositoryPort {
        final List<DonorReportDTO> saved = new ArrayList<>();
        final List<String> donationIds = new ArrayList<>();

        @Override
        public Optional<DonorReportDTO> findByLogicalKey(String donationId, long seq, String hash, String version,
                                                         String model) {
            for (int i = saved.size() - 1; i >= 0; i--) {
                DonorReportDTO r = saved.get(i);
                if (donationIds.get(i).equals(donationId) && r.auditFactsSequence() == seq
                        && Objects.equals(r.sourceFactsHash(), hash) && Objects.equals(r.promptTemplateVersion(), version)
                        && Objects.equals(r.modelIdentifier(), model)) {
                    return Optional.of(r);
                }
            }
            return Optional.empty();
        }

        @Override
        public DonorReportDTO save(String donationId, DonorReportDTO report) {
            donationIds.add(donationId);
            saved.add(report);
            return report;
        }
    }

    @Test
    void aValidFallback_isServedFromTheCache_withoutCallingTheProviderAgain() {
        AuditFactsPort facts = mock(AuditFactsPort.class);
        LlmClientPort llm = mock(LlmClientPort.class);
        InMemoryReports reports = new InMemoryReports();
        AiNarrativeProperties properties = new AiNarrativeProperties();
        when(facts.getAuditFacts("fund-1")).thenReturn(Optional.of(
                new AuditFactsDTO("fund-1", 4L, List.of(), List.of(), Instant.now())));
        when(llm.generateNarrative(any(), anyString())).thenThrow(new NarrativeProviderException("down", null));
        DonorReportGenerator generator = new DonorReportGenerator(facts, llm, new GroundingValidatorImpl(),
                new NarrativePromptSanitizer(100), new FallbackNarrativeTemplateService(properties), reports,
                new NarrativeCacheCoordinator(), properties);

        DonorReportDTO first = generator.generate("fund-1");
        DonorReportDTO second = generator.generate("fund-1");
        DonorReportDTO third = generator.generateAsync("fund-1").getNow(null);

        assertThat(first.source()).isEqualTo(NarrativeSource.FALLBACK_TEMPLATE);
        assertThat(second).isEqualTo(first);
        assertThat(third).as("servido al instante: la ruta responde AVAILABLE, no PENDING").isEqualTo(first);
        verify(llm, times(1)).generateNarrative(any(), anyString());
        assertThat(reports.saved).hasSize(1);
    }
}
