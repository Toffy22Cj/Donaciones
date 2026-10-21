package com.traceability.app.infrastructure.media;

import com.traceability.contracts.media.MediaStoragePort;
import com.traceability.contracts.media.MediaStorageUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Proveedor compatible con S3 (ADR-051 F1), con el <em>bucket</em> privado: nunca se fija ACL ni se genera URL.
 * <p>
 * Guardar reintenta <b>una vez</b>, con la misma clave y los mismos bytes, solo ante un fallo ambiguo (ADR-051 F2):
 * tiempo agotado, E/S cortada con la petición en curso, 408 o 5xx. Un fallo determinista (credenciales, bucket,
 * petición, DNS, conexión rechazada, TLS) no se reintenta. Leer y borrar no se reintentan. Todo fallo termina en
 * {@link MediaStorageUnavailableException}, sin causa encadenada.
 * <p>
 * El log lleva la clave, el intento, la clase de la excepción, el estado HTTP y el código de error del proveedor;
 * nunca el mensaje del SDK ni las credenciales (ADR-051 F1).
 */
public class S3MediaStorageAdapter implements MediaStoragePort, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(S3MediaStorageAdapter.class);
    /** Las claves las genera {@code MediaUploadService} (ULID, ADR-051 F2); otra cosa es un error de programación. */
    private static final Pattern ULID = Pattern.compile("[0-9A-HJKMNP-TV-Z]{26}");
    private static final int PUT_ATTEMPTS = 2;

    private final S3Client client;
    private final String bucket;

    public S3MediaStorageAdapter(S3Client client, String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    @Override
    public void put(String objectKey, byte[] content, String contentType) {
        requireKey(objectKey);
        for (int attempt = 1; ; attempt++) {
            try {
                client.putObject(PutObjectRequest.builder().bucket(bucket).key(objectKey).contentType(contentType)
                        .build(), RequestBody.fromBytes(content));
                return;
            } catch (SdkException e) {
                boolean retry = attempt < PUT_ATTEMPTS && isAmbiguous(e);
                logFailure("put", objectKey, attempt, retry, e);
                if (!retry) {
                    throw unavailable();
                }
            }
        }
    }

    @Override
    public Optional<byte[]> get(String objectKey) {
        requireKey(objectKey);
        try {
            return Optional.of(client.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(objectKey).build())
                    .asByteArray());
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (SdkException e) {
            logFailure("get", objectKey, 1, false, e);
            throw unavailable();
        }
    }

    @Override
    public void delete(String objectKey) {
        requireKey(objectKey);
        try {
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(objectKey).build());
        } catch (SdkException e) {
            logFailure("delete", objectKey, 1, false, e);
            throw unavailable();
        }
    }

    /** Spring lo cierra al parar la aplicación (método de destrucción inferido del bean). */
    @Override
    public void close() {
        client.close();
    }

    /** Fallo cuyo resultado se desconoce: el proveedor pudo guardar o no (ADR-051 F2; reglas 2.6). */
    static boolean isAmbiguous(SdkException e) {
        if (e instanceof ApiCallAttemptTimeoutException || e instanceof ApiCallTimeoutException) {
            return true;
        }
        if (e instanceof AwsServiceException service) {
            int status = service.statusCode();
            return status == 408 || status >= 500;
        }
        if (e instanceof SdkClientException) {
            for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
                if (cause instanceof UnknownHostException || cause instanceof ConnectException
                        || cause instanceof SSLHandshakeException) {
                    return false;
                }
                if (cause instanceof SocketTimeoutException || cause instanceof IOException
                        || cause instanceof UncheckedIOException) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void logFailure(String operation, String objectKey, int attempt, boolean retry, SdkException e) {
        String status = e instanceof AwsServiceException service ? String.valueOf(service.statusCode()) : "-";
        String errorCode = e instanceof AwsServiceException service && service.awsErrorDetails() != null
                && service.awsErrorDetails().errorCode() != null ? service.awsErrorDetails().errorCode() : "-";
        String line = "media.storage.{}.failed objectKey={} attempt={} retry={} cause={} status={} errorCode={}";
        Object[] args = {operation, objectKey, attempt, retry, e.getClass().getSimpleName(), status, errorCode};
        if (retry || isAmbiguous(e)) {
            log.warn(line, args);
        } else {
            log.error(line, args);
        }
    }

    private static MediaStorageUnavailableException unavailable() {
        return new MediaStorageUnavailableException("Media storage unavailable");
    }

    private static void requireKey(String objectKey) {
        if (objectKey == null || !ULID.matcher(objectKey).matches()) {
            throw new IllegalArgumentException("The object key must be a ULID");
        }
    }
}
