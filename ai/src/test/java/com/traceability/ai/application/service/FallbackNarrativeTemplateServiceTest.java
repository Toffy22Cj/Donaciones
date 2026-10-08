package com.traceability.ai.application.service;

import com.traceability.ai.application.config.AiNarrativeProperties;
import com.traceability.ai.domain.narrative.DonorReportDTO;
import com.traceability.contracts.AuditFactsDTO;
import com.traceability.contracts.FinancialFlagDTO;
import com.traceability.contracts.TransitionFactDTO;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * H-DEMO-2 (encargo 5, punto 5): el texto de respaldo de la narrativa individual se muestra en el seguimiento público,
 * así que va en español y no lleva identificadores (ni el {@code fundId} ni el número de secuencia interno).
 */
class FallbackNarrativeTemplateServiceTest {

    private static final String FUND_ID = "01J9ZK3Q7M2X8B4N6P0R5T1V3W";
    private static final long SEQUENCE = 4711L;

    private final FallbackNarrativeTemplateService service;

    FallbackNarrativeTemplateServiceTest() {
        AiNarrativeProperties properties = new AiNarrativeProperties();
        properties.setFallbackRetryInterval(Duration.ofMinutes(15));
        properties.setPromptTemplateVersion("v1");
        service = new FallbackNarrativeTemplateService(properties);
    }

    private String text(int transitions, int flags) {
        AuditFactsDTO facts = new AuditFactsDTO(FUND_ID, SEQUENCE,
                Collections.nCopies(transitions, mock(TransitionFactDTO.class)),
                Collections.nCopies(flags, mock(FinancialFlagDTO.class)), Instant.now());
        DonorReportDTO report = service.createFallback(facts, "hash");
        return report.narrativeText();
    }

    @Test
    void theText_isInSpanish_withTheCountsOnly() {
        assertThat(text(3, 1)).isEqualTo("La narrativa detallada de tu donación no está disponible en este momento. "
                + "Por ahora tiene registrados 3 cambios de estado y 1 alerta financiera.");
        assertThat(text(1, 0)).isEqualTo("La narrativa detallada de tu donación no está disponible en este momento. "
                + "Por ahora tiene registrados 1 cambio de estado y 0 alertas financieras.");
    }

    @Test
    void theText_carriesNoIdentifier() {
        for (String t : List.of(text(0, 0), text(2, 2))) {
            assertThat(t).doesNotContain(FUND_ID).doesNotContain(String.valueOf(SEQUENCE))
                    .doesNotContainIgnoringCase("fund").doesNotContainIgnoringCase("sequence");
        }
    }
}
