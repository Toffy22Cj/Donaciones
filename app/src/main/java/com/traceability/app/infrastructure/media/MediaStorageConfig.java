package com.traceability.app.infrastructure.media;

import com.traceability.app.application.media.ImageSanitizer;
import com.traceability.contracts.media.MediaStoragePort;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import javax.imageio.ImageIO;
import java.net.URI;
import java.nio.file.Path;

/**
 * Selección del almacenamiento de imágenes con <em>fail-fast</em> (ADR-051 F1), con el patrón de ADR-047 D3 y ADR-049
 * D1: un único bean que valida, para que la aplicación no arranque (y diga por qué) en lugar de saltarse el bean con
 * {@code @Profile} o {@code @ConditionalOnProperty}.
 * <ul>
 *   <li>{@code traceability.media.storage} ausente o distinto de {@code s3}/{@code local} → no arranca;</li>
 *   <li>{@code local} sin el perfil {@code dev} → no arranca;</li>
 *   <li>{@code s3} con cualquiera de los cinco valores ausente, vacío o sin resolver ({@code ${…}}) → no arranca. El
 *       mensaje nombra la variable, nunca su valor.</li>
 * </ul>
 * Construir el cliente no llama a la red.
 */
@Configuration
@EnableConfigurationProperties(MediaStorageProperties.class)
public class MediaStorageConfig {

    @Bean
    public MediaStoragePort mediaStoragePort(MediaStorageProperties properties, Environment environment) {
        String storage = properties.storage() == null ? "" : properties.storage().strip();
        return switch (storage) {
            case "s3" -> {
                MediaStorageProperties.S3 s3 = requireS3(properties.s3());
                yield new S3MediaStorageAdapter(s3Client(s3), s3.bucket().strip());
            }
            case "local" -> {
                if (!environment.acceptsProfiles(Profiles.of("dev"))) {
                    throw new IllegalStateException(
                            "traceability.media.storage=local solo se admite con el perfil dev (ADR-051 F1)");
                }
                String directory = properties.local() == null ? null : properties.local().directory();
                if (missing(directory)) {
                    throw new IllegalStateException(
                            "traceability.media.local.directory es obligatorio con traceability.media.storage=local");
                }
                yield new LocalMediaStorageAdapter(Path.of(directory.strip()));
            }
            default -> throw new IllegalStateException(
                    "traceability.media.storage debe ser s3 o local (ADR-051 F1)");
        };
    }

    @Bean
    public ImageSanitizer imageSanitizer() {
        // Ajuste GLOBAL de la JVM, una sola vez (aprobación del plan de la Tarea 1): ImageIO no usa ficheros temporales
        // de disco como caché de sus flujos. La limpieza ya trabaja con flujos MemoryCache*; esto cubre además
        // cualquier ImageIO.createImageInputStream del proceso, para que los bytes de una foto nunca toquen el disco.
        ImageIO.setUseCache(false);
        return new ImageSanitizer();
    }

    /**
     * Cliente S3 de ADR-051 F1: endpoint y región del entorno, credenciales estáticas (nunca la cadena por defecto del
     * SDK, que leería {@code AWS_*} o {@code ~/.aws} en silencio), estilo de ruta (MinIO y R2), cliente HTTP de la JDK,
     * <b>sin reintentos del SDK</b> (el único reintento es el de F2, en el adaptador) y sumas de comprobación solo
     * cuando el proveedor las exige (riesgo R-2 del plan).
     */
    public static S3Client s3Client(MediaStorageProperties.S3 s3) {
        return S3Client.builder()
                .endpointOverride(URI.create(s3.endpoint().strip()))
                .region(Region.of(s3.region().strip()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(s3.accessKeyId().strip(), s3.secretAccessKey().strip())))
                .forcePathStyle(true)
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(s3.connectionTimeout())
                        .socketTimeout(s3.socketTimeout()))
                .overrideConfiguration(o -> o
                        .apiCallAttemptTimeout(s3.attemptTimeout())
                        .retryStrategy(AwsRetryStrategy.doNotRetry()))
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .build();
    }

    private static MediaStorageProperties.S3 requireS3(MediaStorageProperties.S3 s3) {
        require("MEDIA_S3_ENDPOINT", s3 == null ? null : s3.endpoint());
        require("MEDIA_S3_REGION", s3.region());
        require("MEDIA_S3_BUCKET", s3.bucket());
        require("MEDIA_S3_ACCESS_KEY_ID", s3.accessKeyId());
        require("MEDIA_S3_SECRET_ACCESS_KEY", s3.secretAccessKey());
        return s3;
    }

    private static void require(String variable, String value) {
        if (missing(value)) {
            throw new IllegalStateException(variable
                    + " es obligatoria con traceability.media.storage=s3 (ADR-051 F1); sin ella la aplicación no arranca");
        }
    }

    private static boolean missing(String value) {
        return value == null || value.isBlank() || value.contains("${");
    }
}
