package com.traceability.ai.application.service;

import com.traceability.ai.application.config.AiNarrativeProperties;
import com.traceability.ai.domain.narrative.DonorReportDTO;
import com.traceability.ai.domain.narrative.NarrativeSource;
import com.traceability.contracts.AuditFactsDTO;
import org.springframework.stereotype.Service;
import java.time.Instant;

@Service
public class FallbackNarrativeTemplateService {

    /** El fallback no lo produce ningún modelo: se guarda con este identificador en lugar del modelo configurado. */
    public static final String MODEL_IDENTIFIER = "FALLBACK";


    private final AiNarrativeProperties properties;

    public FallbackNarrativeTemplateService(AiNarrativeProperties properties) {
        this.properties = properties;
    }

    private static String count(int n, String singular, String plural) {
        return n + " " + (n == 1 ? singular : plural);
    }

    public DonorReportDTO createFallback(AuditFactsDTO facts, String sourceFactsHash) {
        // H-DEMO-2: se muestra en el seguimiento público; en español y sin identificadores (ni fundId ni secuencia)
        String text = "La narrativa detallada de tu donación no está disponible en este momento. Por ahora tiene registrados "
                + count(facts.transitions().size(), "cambio de estado", "cambios de estado") + " y "
                + count(facts.financialFlags().size(), "alerta financiera", "alertas financieras") + ".";

        Instant now = Instant.now();
        Instant nextRetry = now.plus(properties.getFallbackRetryInterval());

        return new DonorReportDTO(
            text,
            NarrativeSource.FALLBACK_TEMPLATE,
            MODEL_IDENTIFIER,
            properties.getPromptTemplateVersion(),
            sourceFactsHash,
            facts.auditFactsSequence(),
            now,
            nextRetry
        );
    }
}
