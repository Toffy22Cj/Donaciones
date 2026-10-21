package com.traceability.app.infrastructure.media;

import com.traceability.app.application.media.ImageSanitizer;
import com.traceability.contracts.media.MediaStoragePort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Selección del almacenamiento y <em>fail-fast</em> (ADR-051 F1; definición de hecho 18 y 19): fuera de {@code dev},
 * {@code local} no se admite y faltar cualquier valor de S3 impide arrancar; los mensajes nombran la variable, nunca su
 * valor; ningún fichero versionado da un valor por defecto a {@code MEDIA_S3_*}.
 */
@ExtendWith(OutputCaptureExtension.class)
class MediaStorageConfigTest {

    static final String ACCESS_KEY_ID = "AKIDTESTONLYMEDIA001";
    static final String SECRET = "test-only-media-secret-value-0001";

    static final String[][] S3_VALUES = {
            {"MEDIA_S3_ENDPOINT", "traceability.media.s3.endpoint", "http://localhost:1"},
            {"MEDIA_S3_REGION", "traceability.media.s3.region", "us-east-1"},
            {"MEDIA_S3_BUCKET", "traceability.media.s3.bucket", "tests-media"},
            {"MEDIA_S3_ACCESS_KEY_ID", "traceability.media.s3.access-key-id", ACCESS_KEY_ID},
            {"MEDIA_S3_SECRET_ACCESS_KEY", "traceability.media.s3.secret-access-key", SECRET}};

    @TempDir
    Path localDirectory;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MediaStorageConfig.class);

    private static String[] completeS3(String... extra) {
        List<String> values = new ArrayList<>(List.of("traceability.media.storage=s3"));
        for (String[] value : S3_VALUES) {
            values.add(value[1] + "=" + value[2]);
        }
        values.addAll(List.of(extra));
        return values.toArray(String[]::new);
    }

    private static String[] s3Without(String property, String replacement) {
        List<String> values = new ArrayList<>(List.of("traceability.media.storage=s3"));
        for (String[] value : S3_VALUES) {
            if (!value[1].equals(property)) {
                values.add(value[1] + "=" + value[2]);
            } else if (replacement != null) {
                values.add(value[1] + "=" + replacement);
            }
        }
        return values.toArray(String[]::new);
    }

    private ApplicationContextRunner dev() {
        return runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("dev"));
    }

    static String rootMessage(Throwable t) {
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return String.valueOf(t.getMessage());
    }

    @Test
    void completeS3_starts_withTheS3Adapter_withoutCallingTheProvider() {
        // el endpoint no escucha: si el arranque llamara a la red, fallaría
        runner.withPropertyValues(completeS3()).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(MediaStoragePort.class)).isInstanceOf(S3MediaStorageAdapter.class);
        });
    }

    @Test
    void withoutDev_localStorage_doesNotStart() {
        runner.withPropertyValues("traceability.media.storage=local",
                "traceability.media.local.directory=" + localDirectory).run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootMessage(context.getStartupFailure())).contains("dev");
        });
        // ni siquiera con S3 completo al lado
        runner.withPropertyValues(completeS3("traceability.media.storage=local",
                "traceability.media.local.directory=" + localDirectory)).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void withDev_localStorage_starts_withTheLocalAdapter() {
        dev().withPropertyValues("traceability.media.storage=local",
                "traceability.media.local.directory=" + localDirectory).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(MediaStoragePort.class)).isInstanceOf(LocalMediaStorageAdapter.class);
        });
    }

    @Test
    void withDev_s3Storage_alsoStarts() {
        dev().withPropertyValues(completeS3()).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(MediaStoragePort.class)).isInstanceOf(S3MediaStorageAdapter.class);
        });
    }

    static Stream<Arguments> missingS3Values() {
        List<Arguments> cases = new ArrayList<>();
        for (String[] value : S3_VALUES) {
            cases.add(Arguments.of(value[0], value[1], null));
            cases.add(Arguments.of(value[0], value[1], ""));
            cases.add(Arguments.of(value[0], value[1], "   "));
            cases.add(Arguments.of(value[0], value[1], "${" + value[0] + "}"));
        }
        return cases.stream();
    }

    // ADR-051, definición de hecho 18: faltar cualquier valor de S3 impide arrancar
    @ParameterizedTest(name = "{0} = [{2}]")
    @MethodSource("missingS3Values")
    void withoutDev_anyMissingS3Value_doesNotStart_namingTheVariable(String variable, String property, String value) {
        runner.withPropertyValues(s3Without(property, value)).run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootMessage(context.getStartupFailure())).contains(variable)
                    .doesNotContain(SECRET).doesNotContain(ACCESS_KEY_ID);
        });
    }

    @Test
    void anyMissingS3Value_preventsStartup_alsoWithDev() {
        dev().withPropertyValues(s3Without("traceability.media.s3.bucket", null)).run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootMessage(context.getStartupFailure())).contains("MEDIA_S3_BUCKET");
        });
    }

    @Test
    void unknownOrMissingStorage_doesNotStart() {
        runner.withPropertyValues(completeS3("traceability.media.storage=ftp")).run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootMessage(context.getStartupFailure())).contains("traceability.media.storage");
        });
        runner.withPropertyValues(completeS3("traceability.media.storage=")).run(context -> assertThat(context).hasFailed());
        runner.run(context -> assertThat(context).hasFailed());
    }

    // ADR-051, definición de hecho 19 (configuración): ni el mensaje ni el log llevan una credencial
    @Test
    void failFastMessages_nameTheVariable_neverTheCredentials(CapturedOutput output) {
        runner.withPropertyValues(s3Without("traceability.media.s3.bucket", null)).run(context -> {
            assertThat(context).hasFailed();
            Throwable failure = context.getStartupFailure();
            for (Throwable t = failure; t != null; t = t.getCause()) {
                assertThat(String.valueOf(t.getMessage())).doesNotContain(SECRET).doesNotContain(ACCESS_KEY_ID);
            }
        });
        assertThat(output.getAll()).doesNotContain(SECRET).doesNotContain(ACCESS_KEY_ID);
    }

    @Test
    void propertiesToString_hidesTheS3Values() {
        MediaStorageProperties properties = new MediaStorageProperties("s3",
                new MediaStorageProperties.S3("http://localhost:1", "us-east-1", "tests-media", ACCESS_KEY_ID, SECRET,
                        Duration.ofSeconds(3), Duration.ofSeconds(10), Duration.ofSeconds(10)),
                new MediaStorageProperties.Local(null));

        assertThat(properties.toString()).doesNotContain(SECRET).doesNotContain(ACCESS_KEY_ID);
        assertThat(properties.s3().toString()).doesNotContain(SECRET).doesNotContain(ACCESS_KEY_ID);
    }

    // P-4: conexión 3 s, socket 10 s e intento 10 s por defecto
    @Test
    void s3Timeouts_haveTheApprovedDefaults() {
        runner.withPropertyValues(completeS3()).run(context -> {
            MediaStorageProperties.S3 s3 = context.getBean(MediaStorageProperties.class).s3();
            assertThat(s3.connectionTimeout()).isEqualTo(Duration.ofSeconds(3));
            assertThat(s3.socketTimeout()).isEqualTo(Duration.ofSeconds(10));
            assertThat(s3.attemptTimeout()).isEqualTo(Duration.ofSeconds(10));
        });
    }

    // Aprobación del plan, punto 2: ImageIO.setUseCache(false) una sola vez, en la configuración
    @Test
    void theConfiguration_providesTheSanitizer_andDisablesTheImageIoDiskCache() {
        ImageIO.setUseCache(true);
        runner.withPropertyValues(completeS3()).run(context -> {
            assertThat(context).hasSingleBean(ImageSanitizer.class);
            assertThat(ImageIO.getUseCache()).isFalse();
        });
    }

    // ── Ficheros versionados (ADR-051 F1: nunca en ficheros versionados; ADR-047 D3) ──

    @Test
    void mainApplicationYml_takesEveryMediaS3VariableOnlyFromTheEnvironment_withoutADefault() throws Exception {
        // el application.yml de test define valores propios, así que se lee el principal como texto
        String yml = Files.readString(Path.of("src/main/resources/application.yml"));
        for (String[] value : S3_VALUES) {
            assertThat(yml).contains("${" + value[0] + "}");
        }
        assertThat(Pattern.compile("\\$\\{MEDIA_S3_[A-Z_]+:").matcher(yml).find()).isFalse();
        assertThat(yml).containsPattern("(?m)^\\s+storage: s3$");
    }

    @Test
    void devYml_selectsLocal_andHasNoMediaS3Value() throws Exception {
        String yml = Files.readString(Path.of("src/main/resources/application-dev.yml"));
        assertThat(yml).contains("storage: ${TRACEABILITY_MEDIA_STORAGE:local}").doesNotContain("MEDIA_S3");
    }

    // Aprobación del plan, punto 1: .env.example lista las cinco variables vacías
    @Test
    void envExample_listsTheFiveMediaS3VariablesEmpty() throws Exception {
        String example = Files.readString(Path.of("../.env.example"));
        for (String[] value : S3_VALUES) {
            assertThat(example).containsPattern("(?m)^" + value[0] + "=$");
        }
    }
}
