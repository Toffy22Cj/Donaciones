package com.traceability.api.auth.jwt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Definición de hecho de ADR-047 (P4), punto 7: la aplicación no arranca sin secreto, con un secreto de menos de
 * 32 bytes, con clave anterior sin {@code accept-until} o con un {@code accept-until} más allá de arranque + {@code ttl}.
 */
@ExtendWith(OutputCaptureExtension.class)
class JwtSecurityPropertiesTest {

    private static final String SECRET = "a-valid-signing-secret-of-32-bytes!!";   // 36 bytes
    private static final String PREVIOUS = "the-previous-signing-secret-32-bytes";   // 36 bytes

    @Configuration
    @EnableConfigurationProperties(JwtSecurityProperties.class)
    static class Config {}

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

    private ApplicationContextRunner valid() {
        return runner.withPropertyValues(
                "traceability.security.jwt.signing-secret=" + SECRET,
                "traceability.security.jwt.kid=k2");
    }

    @Test
    void startsWithAValidSecretAndKid() {
        valid().run(context -> {
            assertThat(context).hasNotFailed();
            JwtSecurityProperties p = context.getBean(JwtSecurityProperties.class);
            assertThat(p.getTtl()).isEqualTo(Duration.ofHours(8));
            assertThat(p.getClockSkew()).isEqualTo(Duration.ofSeconds(30));
            assertThat(p.hasPreviousKey()).isFalse();
        });
    }

    @Test
    void doesNotStart_withoutSecret_blank_orUnresolved() {
        runner.withPropertyValues("traceability.security.jwt.kid=k2")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("traceability.security.jwt.kid=k2", "traceability.security.jwt.signing-secret=   ")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("traceability.security.jwt.kid=k2",
                        "traceability.security.jwt.signing-secret=${JWT_SIGNING_SECRET_THAT_DOES_NOT_EXIST_ANYWHERE}")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void doesNotStart_withASecretShorterThan32Bytes_andTheErrorDoesNotContainIt() {
        String shortSecret = "only-31-bytes-long-secret-value";
        assertThat(shortSecret.getBytes()).hasSize(31);
        runner.withPropertyValues("traceability.security.jwt.kid=k2", "traceability.security.jwt.signing-secret=" + shortSecret)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(fullMessage(context.getStartupFailure())).doesNotContain(shortSecret);
                });
    }

    @Test
    void doesNotStart_withoutKid() {
        runner.withPropertyValues("traceability.security.jwt.signing-secret=" + SECRET)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void doesNotStart_withAPreviousKeyWithoutAcceptUntil() {
        valid().withPropertyValues(
                        "traceability.security.jwt.previous-signing-secret=" + PREVIOUS,
                        "traceability.security.jwt.previous-kid=k1")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void doesNotStart_withAnAcceptUntilBeyondStartupPlusTtl_orUnparseable() {
        valid().withPropertyValues(
                        "traceability.security.jwt.previous-signing-secret=" + PREVIOUS,
                        "traceability.security.jwt.previous-kid=k1",
                        "traceability.security.jwt.previous-accept-until=" + Instant.now().plus(Duration.ofHours(9)))
                .run(context -> assertThat(context).hasFailed());
        valid().withPropertyValues(
                        "traceability.security.jwt.previous-signing-secret=" + PREVIOUS,
                        "traceability.security.jwt.previous-kid=k1",
                        "traceability.security.jwt.previous-accept-until=tomorrow")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void doesNotStart_withAShortPreviousSecret_aMissingPreviousKid_orTheSameKidTwice() {
        String until = Instant.now().plus(Duration.ofHours(1)).toString();
        valid().withPropertyValues(
                        "traceability.security.jwt.previous-signing-secret=too-short",
                        "traceability.security.jwt.previous-kid=k1",
                        "traceability.security.jwt.previous-accept-until=" + until)
                .run(context -> assertThat(context).hasFailed());
        valid().withPropertyValues(
                        "traceability.security.jwt.previous-signing-secret=" + PREVIOUS,
                        "traceability.security.jwt.previous-accept-until=" + until)
                .run(context -> assertThat(context).hasFailed());
        valid().withPropertyValues(
                        "traceability.security.jwt.previous-signing-secret=" + PREVIOUS,
                        "traceability.security.jwt.previous-kid=k2",
                        "traceability.security.jwt.previous-accept-until=" + until)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void doesNotStart_withAnAcceptUntilButNoPreviousSecret() {
        valid().withPropertyValues(
                        "traceability.security.jwt.previous-kid=k1",
                        "traceability.security.jwt.previous-accept-until=" + Instant.now().plus(Duration.ofHours(1)))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void emptyPreviousSettings_meanNoPreviousKey() {
        valid().withPropertyValues(
                        "traceability.security.jwt.previous-signing-secret=",
                        "traceability.security.jwt.previous-kid=",
                        "traceability.security.jwt.previous-accept-until=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(JwtSecurityProperties.class).hasPreviousKey()).isFalse();
                });
    }

    @Test
    void aValidPreviousKey_startsAndWarnsWithItsKidAndEnd_neverWithTheSecret(CapturedOutput output) {
        Instant until = Instant.now().plus(Duration.ofHours(1));
        valid().withPropertyValues(
                        "traceability.security.jwt.previous-signing-secret=" + PREVIOUS,
                        "traceability.security.jwt.previous-kid=k1",
                        "traceability.security.jwt.previous-accept-until=" + until)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(JwtSecurityProperties.class).hasPreviousKey()).isTrue();
                });

        assertThat(output.getOut() + output.getErr())
                .contains("WARN").contains("k1").contains(until.toString())
                .doesNotContain(PREVIOUS).doesNotContain(SECRET);
    }

    private static String fullMessage(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            sb.append(c).append('\n');
        }
        return sb.toString();
    }
}
