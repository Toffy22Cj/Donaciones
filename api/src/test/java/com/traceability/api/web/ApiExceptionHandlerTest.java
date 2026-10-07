package com.traceability.api.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.core.application.authorization.CrossOrganizationAccessException;
import com.traceability.core.application.authorization.InsufficientRoleException;
import com.traceability.core.application.command.ConcurrencyRetryExhaustedException;
import com.traceability.core.application.exception.ConcurrencyConflictException;
import com.traceability.core.application.exception.InKindCampaignNotFoundException;
import com.traceability.core.application.exception.InKindCampaignOfOtherOrganizationException;
import com.traceability.core.domain.physicalasset.exceptions.AssetTerminalStateException;
import com.traceability.core.domain.physicalasset.exceptions.InvalidAssetTransitionException;
import com.traceability.core.domain.shared.exceptions.AggregateNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Plan B6-0 §2.3, tests 5, 6, 7 y 12. Cada excepción lleva {@link #MARKER} en el mensaje: ningún cuerpo ni log de 500
 * puede contenerlo.
 */
@ExtendWith(OutputCaptureExtension.class)
class ApiExceptionHandlerTest {

    static final String MARKER = "SECRET-MARKER-7f3a";
    static final ObjectMapper JSON = new ObjectMapper();

    static final Map<String, Supplier<RuntimeException>> THROWERS = Map.ofEntries(
            Map.entry("crossOrg", () -> new CrossOrganizationAccessException(MARKER)),
            Map.entry("role", () -> new InsufficientRoleException(MARKER)),
            Map.entry("notFound", () -> new AggregateNotFoundException(MARKER)),
            Map.entry("invalidTransition", () -> new InvalidAssetTransitionException(MARKER)),
            Map.entry("terminal", () -> new AssetTerminalStateException(MARKER)),
            Map.entry("campaignNotFound", () -> new InKindCampaignNotFoundException(MARKER)),
            Map.entry("campaignOtherOrg", () -> new InKindCampaignOfOtherOrganizationException(MARKER)),
            Map.entry("illegalArgument", () -> new IllegalArgumentException(MARKER)),
            Map.entry("conflict", () -> new ConcurrencyConflictException(MARKER)),
            Map.entry("retryExhausted", () -> new ConcurrencyRetryExhaustedException(MARKER)),
            Map.entry("unexpected", () -> new IllegalStateException(MARKER)),
            Map.entry("unexpectedChecked", () -> new RuntimeException(new java.io.IOException(MARKER))));

    record Body(String text) {}

    /** Solo de test. */
    @RestController
    static class ThrowingController {
        @GetMapping("/t/throw/{kind}")
        String doThrow(@PathVariable String kind) {
            throw THROWERS.get(kind).get();
        }

        @PostMapping("/t/body")
        String body(@RequestBody Body body) {
            return body.text();
        }
    }

    private final MockMvc mvc = WebTestSupport.mvc(new ThrowingController());

    private MockHttpServletResponse call(String kind) throws Exception {
        return mvc.perform(get("/t/throw/" + kind)).andReturn().getResponse();
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> table() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("crossOrg", 403, "Forbidden"),
                org.junit.jupiter.params.provider.Arguments.of("role", 403, "Forbidden"),
                org.junit.jupiter.params.provider.Arguments.of("notFound", 404, "NotFound"),
                org.junit.jupiter.params.provider.Arguments.of("invalidTransition", 409, "InvalidAssetTransition"),
                org.junit.jupiter.params.provider.Arguments.of("terminal", 409, "AssetTerminalState"),
                org.junit.jupiter.params.provider.Arguments.of("campaignNotFound", 409, "CampaignNotAvailable"),
                org.junit.jupiter.params.provider.Arguments.of("campaignOtherOrg", 409, "CampaignNotAvailable"),
                org.junit.jupiter.params.provider.Arguments.of("illegalArgument", 400, "BadRequest"),
                org.junit.jupiter.params.provider.Arguments.of("conflict", 409, "ConcurrentModification"),
                org.junit.jupiter.params.provider.Arguments.of("retryExhausted", 409, "ConcurrentModification"),
                org.junit.jupiter.params.provider.Arguments.of("unexpected", 500, "InternalError"),
                org.junit.jupiter.params.provider.Arguments.of("unexpectedChecked", 500, "InternalError"));
    }

    @ParameterizedTest(name = "{0} -> {1} {2}")
    @MethodSource("table")
    void eachRowOfTheTable_givesItsStatusAndProblemDetail_withoutTheExceptionMessage(String kind, int status, String title)
            throws Exception {
        MockHttpServletResponse r = call(kind);

        assertThat(r.getStatus()).isEqualTo(status);
        assertThat(r.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        JsonNode body = JSON.readTree(r.getContentAsString());
        assertThat(body.get("status").asInt()).isEqualTo(status);
        assertThat(body.get("title").asText()).isEqualTo(title);
        assertThat(body.get("detail").asText()).isNotBlank();
        assertThat(r.getContentAsString()).doesNotContain(MARKER).doesNotContain("Exception").doesNotContain("com.traceability");
    }

    @Test
    void the403_isIdenticalByteForByte_forOtherOrganizationAndMissingRole() throws Exception {
        assertThat(call("crossOrg").getContentAsByteArray()).isEqualTo(call("role").getContentAsByteArray());
    }

    @Test
    void theCampaign409_isIdenticalByteForByte_forNotFoundAndOtherOrganization() throws Exception {
        assertThat(call("campaignNotFound").getContentAsByteArray()).isEqualTo(call("campaignOtherOrg").getContentAsByteArray());
    }

    @Test
    void the500_carriesACorrelationId_thatIsInTheErrorLog_withClassButNoMessageNorStackTrace(CapturedOutput output)
            throws Exception {
        MockHttpServletResponse r = call("unexpected");

        JsonNode body = JSON.readTree(r.getContentAsString());
        String correlationId = body.get(ApiExceptionHandler.CORRELATION_ID).asText();
        assertThat(UUID.fromString(correlationId)).isNotNull();
        assertThat(output.getOut())
                .contains("ERROR")
                .contains(correlationId)
                .contains(IllegalStateException.class.getName())
                .doesNotContain(MARKER)
                .doesNotContain("\tat ");
    }

    @Test
    void the500_logDoesNotIncludeTheMessageOfTheCause_either(CapturedOutput output) throws Exception {
        MockHttpServletResponse r = call("unexpectedChecked");

        assertThat(r.getStatus()).isEqualTo(500);
        assertThat(output.getOut()).doesNotContain(MARKER).doesNotContain("Caused by");
    }

    @Test
    void each500_hasItsOwnCorrelationId() throws Exception {
        String first = JSON.readTree(call("unexpected").getContentAsString()).get(ApiExceptionHandler.CORRELATION_ID).asText();
        String second = JSON.readTree(call("unexpected").getContentAsString()).get(ApiExceptionHandler.CORRELATION_ID).asText();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void anUnreadableBody_is400_withoutEchoingTheInput() throws Exception {
        MockHttpServletResponse r = mvc.perform(post("/t/body")
                .contentType(MediaType.APPLICATION_JSON).content("{\"text\": " + MARKER + "}")).andReturn().getResponse();

        assertThat(r.getStatus()).isEqualTo(400);
        assertThat(JSON.readTree(r.getContentAsString()).get("title").asText()).isEqualTo("BadRequest");
        assertThat(r.getContentAsString()).doesNotContain(MARKER);
    }

    @Test
    void springErrorResponses_keepTheirStatus_withAFixedBody() throws Exception {
        MockHttpServletResponse r = mvc.perform(delete("/t/body")).andReturn().getResponse();

        assertThat(r.getStatus()).isEqualTo(405);
        assertThat(r.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(JSON.readTree(r.getContentAsString()).get("status").asInt()).isEqualTo(405);
    }
}
