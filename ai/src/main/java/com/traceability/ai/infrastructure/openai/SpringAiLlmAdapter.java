package com.traceability.ai.infrastructure.openai;

import com.traceability.ai.application.port.out.LlmClientPort;
import com.traceability.ai.domain.exception.NarrativeGenerationTimeoutException;
import com.traceability.ai.domain.exception.NarrativeProviderException;
import com.traceability.ai.domain.narrative.CitedFact;
import com.traceability.ai.domain.narrative.FactType;
import com.traceability.ai.domain.narrative.LlmNarrativeResponse;
import com.traceability.contracts.AuditFactsDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class SpringAiLlmAdapter implements LlmClientPort {

    private static final Logger log = LoggerFactory.getLogger(SpringAiLlmAdapter.class);
    private final ChatClient chatClient;

    public SpringAiLlmAdapter(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    /**
     * El prompt del relato del donante. Las reglas de las citas son las mismas que comprueba
     * {@code GroundingValidatorImpl}: una cita que no coincide exactamente con un hecho descarta el relato entero.
     */
    static String prompt(AuditFactsDTO facts, String outputFormat) {
        return "Escribe en español, en dos a cuatro frases sencillas, el relato de lo que pasó con una donación,"
                + " para la persona que donó. Usa solo los hechos de abajo; no inventes nada."
                + "\n\nReglas de citedFacts (se comprueban una por una; si una falla, el relato se descarta):"
                + "\n- Cita cada dato que menciones, con su valor EXACTO tal como aparece en los hechos."
                + "\n- DATE: una fecha ISO-8601 copiada tal cual de occurredAtFrom, occurredAtTo, occurredAt o generatedAt."
                + "\n- AMOUNT: solo el campo amount de una entrada de financialFlags, como número entero sin puntos ni"
                + " símbolos. Nunca uses AMOUNT para cantidades de unidades, conteos ni duraciones."
                + "\n- LIFECYCLE_STATUS y TRANSITION: un fromStatus o toStatus de transitions, copiado tal cual."
                + "\n- OPERATION_REF: el fundId, un assetRef, un allocationId, un refundId o un type de financialFlags."
                + "\n- No escribas en narrativeText ninguna cifra que no esté en citedFacts. Si no hay transiciones ni"
                + " alertas, cita el fundId como OPERATION_REF."
                + "\n- No menciones identificadores internos en narrativeText: descríbelos (\"un lote\", \"la entrega\")."
                + "\n\nDevuelve solo un JSON que cumpla este esquema:\n" + outputFormat
                + "\n\nHechos:\n\"\"\"\n" + facts + "\n\"\"\"";
    }

    @Override
    public LlmNarrativeResponse generateNarrative(AuditFactsDTO sanitizedFacts, String promptTemplateVersion)
            throws NarrativeGenerationTimeoutException, NarrativeProviderException {
        try {
            org.springframework.ai.converter.BeanOutputConverter<LlmNarrativeResponse> converter =
                    new org.springframework.ai.converter.BeanOutputConverter<>(LlmNarrativeResponse.class);

            String format = converter.getFormat();

            String prompt = prompt(sanitizedFacts, format);

            ChatResponse response = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .chatResponse();

            String text = response.getResult().getOutput().getText();
            
            LlmNarrativeResponse generated = converter.convert(text);
            if (generated == null) {
                throw new NarrativeProviderException("LLM returned null structured output", null);
            }
            return generated;
        } catch (Exception e) {
            log.error("Provider exception", e);
            if (e.getMessage() != null && e.getMessage().toLowerCase().contains("timeout")) {
                throw new NarrativeGenerationTimeoutException("Timeout while generating narrative", e);
            }
            throw new NarrativeProviderException("Error communicating with LLM provider", e);
        }
    }
}
