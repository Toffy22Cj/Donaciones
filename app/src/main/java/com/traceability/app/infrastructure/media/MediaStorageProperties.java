package com.traceability.app.infrastructure.media;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuración del almacenamiento de imágenes (ADR-051 F1): {@code traceability.media.storage = s3 | local}, los cinco
 * valores {@code MEDIA_S3_*} y el directorio del adaptador local de {@code dev}. La validación (<em>fail-fast</em>) la
 * hace {@link MediaStorageConfig}, porque depende del perfil activo.
 * <p>
 * {@code toString} nunca muestra los valores de S3 (ADR-051 F1: nunca en logs).
 */
@ConfigurationProperties(prefix = "traceability.media")
public record MediaStorageProperties(String storage, S3 s3, Local local) {

    @Override
    public String toString() {
        return "MediaStorageProperties[storage=" + storage + ", s3=" + s3 + ", local=" + local + "]";
    }

    /**
     * Valores del proveedor. Los tiempos son valores de implementación (plan Tarea 1, P-4): conexión 3 s, socket 10 s y
     * tiempo por intento 10 s por defecto.
     */
    public record S3(String endpoint, String region, String bucket, String accessKeyId, String secretAccessKey,
                     Duration connectionTimeout, Duration socketTimeout, Duration attemptTimeout) {

        public S3 {
            connectionTimeout = connectionTimeout == null ? Duration.ofSeconds(3) : connectionTimeout;
            socketTimeout = socketTimeout == null ? Duration.ofSeconds(10) : socketTimeout;
            attemptTimeout = attemptTimeout == null ? Duration.ofSeconds(10) : attemptTimeout;
        }

        @Override
        public String toString() {
            return "S3[values hidden, connectionTimeout=" + connectionTimeout + ", socketTimeout=" + socketTimeout
                    + ", attemptTimeout=" + attemptTimeout + "]";
        }
    }

    public record Local(String directory) {
    }
}
