package com.traceability.app.config;

import com.traceability.app.web.campaign.DiscoveryCursorCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DD-53 (Carlos, 2026-10-07): la clave del cursor llega solo por {@code TRACEABILITY_DISCOVERY_CURSOR_KEY}, sin valor
 * por defecto; sin ella, o mal formada, o igual a otro secreto, la aplicación no arranca; y nunca aparece en logs ni en
 * mensajes de error.
 */
@ExtendWith(OutputCaptureExtension.class)
class DiscoveryCursorKeyConfigTest {

    static final String KEY = Base64.getEncoder().encodeToString("test-only-cursor-key-32-bytes!!!".getBytes());
    static final String JWT = "test-only-jwt-signing-secret-not-for-production";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(DiscoveryCursorConfig.class)
            .withPropertyValues("traceability.security.jwt.signing-secret=" + JWT,
                    "traceability.security.tracking-code-secret=test-only-tracking-secret-000000000",
                    "traceability.security.asset-ref-secret=test-only-asset-ref-secret-0000000000");

    @Test
    void withAValidDistinctKey_theCodecIsAvailable() {
        runner.withPropertyValues("traceability.discovery.cursor-key=" + KEY).run(context -> {
            assertThat(context).hasNotFailed();
            DiscoveryCursorCodec codec = context.getBean(DiscoveryCursorCodec.class);
            assertThat(codec.decode(codec.encode("01ARZ3NDEKTSV4RRFFQ69G5FAV"))).hasValue("01ARZ3NDEKTSV4RRFFQ69G5FAV");
        });
    }

    @Test
    void withoutTheVariable_theApplicationDoesNotStart() {
        runner.run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("traceability.discovery.cursor-key=").run(context -> assertThat(context).hasFailed());
    }

    @Test
    void aMalformedKey_preventsStartup_withoutRevealingIt(CapturedOutput output) {
        String shortKey = Base64.getEncoder().encodeToString("only-16-bytes!!!".getBytes());
        runner.withPropertyValues("traceability.discovery.cursor-key=" + shortKey).run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootMessage(context.getStartupFailure())).contains("TRACEABILITY_DISCOVERY_CURSOR_KEY")
                    .doesNotContain(shortKey);
        });
        runner.withPropertyValues("traceability.discovery.cursor-key=not base64 at all!").run(context ->
                assertThat(context).hasFailed());
        assertThat(output.getAll()).doesNotContain(shortKey);
    }

    @Test
    void aKeyEqualToAnotherSecret_preventsStartup(CapturedOutput output) {
        String reusedJwt = Base64.getEncoder().encodeToString(JWT.substring(0, 32).getBytes());
        // la misma cadena que otro secreto, o los mismos bytes una vez decodificada
        runner.withPropertyValues("traceability.discovery.cursor-key=" + JWT).run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("traceability.security.asset-ref-secret=" + KEY,
                "traceability.discovery.cursor-key=" + KEY).run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootMessage(context.getStartupFailure())).contains("distinta").doesNotContain(KEY);
        });
        runner.withPropertyValues("traceability.security.jwt.signing-secret=" + JWT.substring(0, 32),
                "traceability.discovery.cursor-key=" + reusedJwt).run(context -> assertThat(context).hasFailed());
        assertThat(output.getAll()).doesNotContain(KEY).doesNotContain(reusedJwt);
    }

    @Test
    void theMainConfiguration_takesTheKeyOnlyFromTheEnvironment_withoutADefault() throws Exception {
        // el application.yml de test define una clave propia, así que se lee el principal como texto
        String yml = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/application.yml"));
        assertThat(yml).contains("cursor-key: ${TRACEABILITY_DISCOVERY_CURSOR_KEY}\n")
                .doesNotContain("${TRACEABILITY_DISCOVERY_CURSOR_KEY:");
    }

    static String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return String.valueOf(t.getMessage());
    }
}
