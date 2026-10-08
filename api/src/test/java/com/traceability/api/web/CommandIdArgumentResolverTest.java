package com.traceability.api.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Plan B6-0 §2.2, tests 4 y 11. */
class CommandIdArgumentResolverTest {

    /** Solo de test: registra los Command-Id que llegan al "caso de uso". */
    @RestController
    static class CommandController {
        final List<String> useCaseCalls = new ArrayList<>();

        @PostMapping("/t/command")
        String command(@CommandId String commandId) {
            useCaseCalls.add(commandId);
            return commandId;
        }
    }

    private final CommandController controller = new CommandController();
    private final MockMvc mvc = WebTestSupport.mvc(controller);

    @Test
    void missingHeader_is400_andTheUseCaseIsNeverCalled() throws Exception {
        MvcResult r = mvc.perform(post("/t/command")).andReturn();

        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(r.getResponse().getContentType()).isEqualTo("application/problem+json");
        assertThat(controller.useCaseCalls).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "not-a-uuid-MARKER", "123", "6f1c2a5e-9b47-5d3a-8e21-4c7b9d0f1a3", "6f1c2a5e9b475d3a8e214c7b9d0f1a36",
            "{6f1c2a5e-9b47-5d3a-8e21-4c7b9d0f1a36}", "6f1c2a5e-9b47-5d3a-8e21-4c7b9d0f1a36 x"})
    void invalidHeader_is400_withoutEchoingTheValue_andTheUseCaseIsNeverCalled(String value) throws Exception {
        MvcResult r = mvc.perform(post("/t/command").header("Command-Id", value)).andReturn();

        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(r.getResponse().getContentAsString()).doesNotContain("MARKER");
        assertThat(controller.useCaseCalls).isEmpty();
    }

    @Test
    void validHeader_reachesTheUseCase_normalizedToLowercase() throws Exception {
        MvcResult r = mvc.perform(post("/t/command").header("Command-Id", "6F1C2A5E-9B47-5D3A-8E21-4C7B9D0F1A36")).andReturn();

        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(controller.useCaseCalls).containsExactly("6f1c2a5e-9b47-5d3a-8e21-4c7b9d0f1a36");
    }

    @Test
    void lowercaseAndUppercaseOfTheSameId_areTheSameCommand() throws Exception {
        mvc.perform(post("/t/command").header("Command-Id", "6f1c2a5e-9b47-5d3a-8e21-4c7b9d0f1a36"));
        mvc.perform(post("/t/command").header("Command-Id", "6F1C2A5E-9B47-5D3A-8E21-4C7B9D0F1A36"));

        assertThat(controller.useCaseCalls).hasSize(2).containsOnly("6f1c2a5e-9b47-5d3a-8e21-4c7b9d0f1a36");
    }
}
