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

    @Override
    public LlmNarrativeResponse generateNarrative(AuditFactsDTO sanitizedFacts, String promptTemplateVersion)
            throws NarrativeGenerationTimeoutException, NarrativeProviderException {
        try {
            org.springframework.ai.converter.BeanOutputConverter<LlmNarrativeResponse> converter =
                    new org.springframework.ai.converter.BeanOutputConverter<>(LlmNarrativeResponse.class);

            String format = converter.getFormat();

            String prompt = String.format("Generate a human-readable donor report for fund %s based on facts. Emphasize impact.\n\n" +
                    "Return a JSON adhering strictly to the following schema:\n%s\n\nRaw facts:\n\"\"\"\n%s\n\"\"\"",
                    sanitizedFacts.fundId(), format, sanitizedFacts.toString());
            
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
