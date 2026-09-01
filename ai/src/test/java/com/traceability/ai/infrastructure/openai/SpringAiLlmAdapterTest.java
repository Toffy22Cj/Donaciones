package com.traceability.ai.infrastructure.openai;

import com.traceability.ai.domain.narrative.CitedFact;
import com.traceability.ai.domain.narrative.FactType;
import com.traceability.ai.domain.narrative.LlmNarrativeResponse;
import com.traceability.contracts.AuditFactsDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class SpringAiLlmAdapterTest {

    private ChatClient.Builder builder;
    private ChatClient chatClient;
    private ChatClient.CallResponseSpec callResponseSpec;
    private SpringAiLlmAdapter adapter;

    @BeforeEach
    void setup() {
        chatClient = mock(ChatClient.class);
        builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);

        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        callResponseSpec = mock(ChatClient.CallResponseSpec.class);

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);

        adapter = new SpringAiLlmAdapter(builder);
    }

    @Test
    void shouldExtractStructuredOutput() throws Exception {
        // Arrange
        AuditFactsDTO facts = new AuditFactsDTO("FUND-123", 1L, Collections.emptyList(), Collections.emptyList(), Instant.now());
        
        // Mock a structured JSON response matching the BeanOutputConverter expectation
        String mockJsonResponse = "{\n" +
                "  \"narrativeText\": \"This is a test narrative.\",\n" +
                "  \"citedFacts\": [\n" +
                "    {\n" +
                "      \"type\": \"OPERATION_REF\",\n" +
                "      \"value\": \"FUND-123\"\n" +
                "    }\n" +
                "  ]\n" +
                "}";

        org.springframework.ai.chat.messages.AssistantMessage assistantMessage = 
                new org.springframework.ai.chat.messages.AssistantMessage(mockJsonResponse);
        Generation generation = new Generation(assistantMessage);
        org.springframework.ai.chat.model.ChatResponse chatResponse = new org.springframework.ai.chat.model.ChatResponse(List.of(generation));

        when(callResponseSpec.chatResponse()).thenReturn(chatResponse);

        // Act
        LlmNarrativeResponse response = adapter.generateNarrative(facts, "v1");

        // Assert
        assertNotNull(response);
        assertEquals("This is a test narrative.", response.narrativeText());
        assertEquals(1, response.citedFacts().size());
        assertEquals(FactType.OPERATION_REF, response.citedFacts().get(0).type());
        assertEquals("FUND-123", response.citedFacts().get(0).value());
    }
}
