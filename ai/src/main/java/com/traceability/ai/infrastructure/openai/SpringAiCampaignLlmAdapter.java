package com.traceability.ai.infrastructure.openai;

import com.traceability.ai.application.port.out.CampaignLlmClientPort;
import com.traceability.ai.application.service.CampaignNarrativePrompt;
import com.traceability.ai.domain.exception.NarrativeGenerationTimeoutException;
import com.traceability.ai.domain.exception.NarrativeProviderException;
import com.traceability.ai.domain.narrative.CampaignLlmNarrativeResponse;
import com.traceability.ai.domain.narrative.CampaignNarrativeFacts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Component;

/**
 * LLM real de la narrativa de convocatoria (plan B5). La clave del proveedor solo llega por la variable de entorno
 * {@code SPRING_AI_OPENAI_API_KEY}; sin ella la llamada falla y la respuesta es "Narrativa no disponible".
 */
@Component
public class SpringAiCampaignLlmAdapter implements CampaignLlmClientPort {

    private static final Logger log = LoggerFactory.getLogger(SpringAiCampaignLlmAdapter.class);
    private final ChatClient chatClient;

    public SpringAiCampaignLlmAdapter(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    @Override
    public CampaignLlmNarrativeResponse generateCampaignNarrative(CampaignNarrativeFacts facts, String promptTemplateVersion)
            throws NarrativeGenerationTimeoutException, NarrativeProviderException {
        try {
            BeanOutputConverter<CampaignLlmNarrativeResponse> converter =
                    new BeanOutputConverter<>(CampaignLlmNarrativeResponse.class);
            String text = chatClient.prompt()
                    .user(CampaignNarrativePrompt.render(facts, converter.getFormat()))
                    .call()
                    .chatResponse()
                    .getResult().getOutput().getText();
            CampaignLlmNarrativeResponse generated = converter.convert(text);
            if (generated == null) {
                throw new NarrativeProviderException("LLM returned null structured output", null);
            }
            return generated;
        } catch (NarrativeProviderException e) {
            throw e;
        } catch (Exception e) {
            log.error("Campaign narrative provider exception: {}", e.getClass().getSimpleName());
            if (e.getMessage() != null && e.getMessage().toLowerCase().contains("timeout")) {
                throw new NarrativeGenerationTimeoutException("Timeout while generating narrative", e);
            }
            throw new NarrativeProviderException("Error communicating with LLM provider", e);
        }
    }
}
