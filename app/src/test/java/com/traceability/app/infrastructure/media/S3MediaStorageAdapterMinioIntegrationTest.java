package com.traceability.app.infrastructure.media;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.traceability.contracts.media.MediaStorageUnavailableException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El adaptador S3 contra MinIO real con Testcontainers ({@code GenericContainer}, sin módulo nuevo): definición de hecho
 * 17 de ADR-051, y la 19 (ninguna credencial en logs) con fallos reales del proveedor y el SDK en DEBUG. El cliente es
 * el de producción ({@link MediaStorageConfig#s3Client}).
 * <p>
 * Imagen: {@code minio/minio} ya no existe en Docker Hub y {@code quay.io/minio/minio} exige autenticación (riesgo R-4
 * del plan); se usa la build de MinIO de Bitnami en su catálogo heredado, fijada por digest, arrancando su propio
 * binario {@code minio}.
 */
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class S3MediaStorageAdapterMinioIntegrationTest {

    static final String ACCESS_KEY_ID = "minioTestOnlyAkid01";
    static final String SECRET = "minio-test-only-secret-0001";
    static final String WRONG_SECRET = "minio-wrong-secret-0002";
    static final String BUCKET = "paxfide-media-it";
    /** Fijada por digest (N-2, Manuel, 2026-10-20): etiqueta 2025.7.23-debian-12-r5, MinIO 2025-07-23 (plan §5). */
    static final String MINIO_IMAGE =
            "bitnamilegacy/minio@sha256:6dabb4a2088c9a79908de3bc05f4586c23ad2182c8908e7e3acbf61c1467fb20";

    @Container
    static GenericContainer<?> minio = new GenericContainer<>(DockerImageName.parse(MINIO_IMAGE))
            .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("minio"))
            .withCommand("server", "/tmp/data")
            .withEnv("MINIO_ROOT_USER", ACCESS_KEY_ID)
            .withEnv("MINIO_ROOT_PASSWORD", SECRET)
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));

    static String endpoint() {
        return "http://" + minio.getHost() + ":" + minio.getMappedPort(9000);
    }

    static MediaStorageProperties.S3 properties(String secret, Duration attemptTimeout) {
        return new MediaStorageProperties.S3(endpoint(), "us-east-1", BUCKET, ACCESS_KEY_ID, secret,
                Duration.ofSeconds(1), attemptTimeout, attemptTimeout);
    }

    static S3MediaStorageAdapter adapter(String secret, Duration attemptTimeout) {
        return new S3MediaStorageAdapter(MediaStorageConfig.s3Client(properties(secret, attemptTimeout)), BUCKET);
    }

    /** Cliente propio del test (no el de producción) para crear el bucket y leer cabeceras. */
    static S3Client admin() {
        return S3Client.builder().endpointOverride(URI.create(endpoint())).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY_ID, SECRET)))
                .forcePathStyle(true).httpClient(UrlConnectionHttpClient.create()).build();
    }

    @BeforeAll
    static void createBucket() {
        try (S3Client admin = admin()) {
            admin.createBucket(b -> b.bucket(BUCKET));
        }
    }

    static String newKey() {
        return com.github.f4b6a3.ulid.UlidCreator.getUlid().toString();
    }

    static long count(String text, String needle) {
        Matcher m = Pattern.compile(Pattern.quote(needle)).matcher(text);
        long n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    @Test
    void putGetDelete_roundTrip_keepsBytesAndContentType() {
        S3MediaStorageAdapter adapter = adapter(SECRET, Duration.ofSeconds(10));
        String key = newKey();
        byte[] bytes = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};

        adapter.put(key, bytes, "image/png");

        assertThat(adapter.get(key)).hasValueSatisfying(stored -> assertThat(stored).isEqualTo(bytes));
        try (S3Client admin = admin()) {
            HeadObjectResponse head = admin.headObject(b -> b.bucket(BUCKET).key(key));
            assertThat(head.contentType()).isEqualTo("image/png");
            assertThat(head.contentLength()).isEqualTo(bytes.length);
        }

        adapter.delete(key);
        assertThat(adapter.get(key)).isEmpty();
    }

    @Test
    void get_missingKey_isEmpty() {
        assertThat(adapter(SECRET, Duration.ofSeconds(10)).get(newKey())).isEmpty();
    }

    // ADR-051 F2: el reintento es seguro porque guardar la misma clave con los mismos bytes es idempotente
    @Test
    void put_sameKeyTwice_isIdempotent() {
        S3MediaStorageAdapter adapter = adapter(SECRET, Duration.ofSeconds(10));
        String key = newKey();
        byte[] bytes = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 9, 9};

        adapter.put(key, bytes, "image/jpeg");
        adapter.put(key, bytes, "image/jpeg");

        assertThat(adapter.get(key)).hasValueSatisfying(stored -> assertThat(stored).isEqualTo(bytes));
    }

    @Test
    void delete_missingKey_doesNotFail() {
        assertThatCode(() -> adapter(SECRET, Duration.ofSeconds(10)).delete(newKey())).doesNotThrowAnyException();
    }

    // ADR-051 F1: bucket privado; ningún objeto tiene URL pública del proveedor
    @Test
    void bucketIsPrivate_anonymousGetIsForbidden() throws Exception {
        String key = newKey();
        adapter(SECRET, Duration.ofSeconds(10)).put(key, new byte[]{1, 2, 3}, "image/png");

        HttpResponse<Void> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(endpoint() + "/" + BUCKET + "/" + key)).GET().build(),
                HttpResponse.BodyHandlers.discarding());

        assertThat(response.statusCode()).isEqualTo(403);
    }

    // credenciales malas: fallo determinista, un solo intento
    @Test
    void badCredentials_failAfterOneAttempt_withoutRetry(CapturedOutput output) {
        String key = newKey();

        assertThatThrownBy(() -> adapter(WRONG_SECRET, Duration.ofSeconds(10)).put(key, new byte[]{1}, "image/png"))
                .isInstanceOf(MediaStorageUnavailableException.class);

        String log = output.getAll();
        assertThat(count(log, "media.storage.put.failed objectKey=" + key + " ")).isEqualTo(1);
        assertThat(log).contains("media.storage.put.failed objectKey=" + key + " attempt=1 retry=false cause=S3Exception status=403 errorCode=SignatureDoesNotMatch");
    }

    // proveedor congelado: tiempo agotado (ambiguo) → un reintento → MediaStorageUnavailableException
    @Test
    void pausedProvider_timesOut_retriesOnce_thenUnavailable(CapturedOutput output) {
        S3MediaStorageAdapter adapter = adapter(SECRET, Duration.ofSeconds(2));
        String key = newKey();
        minio.getDockerClient().pauseContainerCmd(minio.getContainerId()).exec();
        try {
            assertThatThrownBy(() -> adapter.put(key, new byte[]{1, 2}, "image/png"))
                    .isInstanceOf(MediaStorageUnavailableException.class);
        } finally {
            minio.getDockerClient().unpauseContainerCmd(minio.getContainerId()).exec();
        }

        String log = output.getAll();
        assertThat(count(log, "media.storage.put.failed objectKey=" + key + " ")).isEqualTo(2);
        assertThat(log).contains("media.storage.put.failed objectKey=" + key + " attempt=1 retry=true")
                .contains("media.storage.put.failed objectKey=" + key + " attempt=2 retry=false");
    }

    // ADR-051, definición de hecho 19: ninguna credencial del proveedor en logs, ni con el SDK en DEBUG
    @Test
    void noCredentialAppearsInLogs_onProviderFailures_evenWithTheSdkInDebug(CapturedOutput output) {
        Logger sdk = (Logger) LoggerFactory.getLogger("software.amazon.awssdk");
        Level previous = sdk.getLevel();
        sdk.setLevel(Level.DEBUG);
        try {
            S3MediaStorageAdapter wrong = adapter(WRONG_SECRET, Duration.ofSeconds(10));
            assertThatThrownBy(() -> wrong.put(newKey(), new byte[]{1}, "image/png"))
                    .isInstanceOf(MediaStorageUnavailableException.class);
            assertThatThrownBy(() -> wrong.get(newKey())).isInstanceOf(MediaStorageUnavailableException.class);
            assertThatThrownBy(() -> wrong.delete(newKey())).isInstanceOf(MediaStorageUnavailableException.class);
            S3MediaStorageAdapter right = adapter(SECRET, Duration.ofSeconds(10));
            String key = newKey();
            right.put(key, new byte[]{1}, "image/png");
            right.get(key);
        } finally {
            sdk.setLevel(previous);
        }

        assertThat(output.getAll()).doesNotContain(ACCESS_KEY_ID).doesNotContain(SECRET).doesNotContain(WRONG_SECRET);
    }
}
