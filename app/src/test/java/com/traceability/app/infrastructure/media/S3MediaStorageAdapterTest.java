package com.traceability.app.infrastructure.media;

import com.traceability.contracts.media.MediaStorageUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Adaptador S3 con un {@code S3Client} simulado (ADR-051 F1, F2): el único reintento de un guardado ambiguo, con la
 * misma clave y los mismos bytes, y ningún reintento ante un fallo determinista (definición de hecho 15, a nivel del
 * adaptador; el 503 por HTTP es de la Tarea 2).
 */
@ExtendWith(OutputCaptureExtension.class)
class S3MediaStorageAdapterTest {

    static final String BUCKET = "media-bucket";
    static final String KEY = "01JB2Z3Y4X5W6V7T8S9R0QPNMK";
    static final byte[] BYTES = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3, 4};

    private S3Client s3;
    private S3MediaStorageAdapter adapter;

    @BeforeEach
    void setUp() {
        s3 = mock(S3Client.class);
        adapter = new S3MediaStorageAdapter(s3, BUCKET);
    }

    static S3Exception s3Error(int status, String errorCode) {
        return (S3Exception) S3Exception.builder().statusCode(status).message("provider says " + errorCode)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode(errorCode).errorMessage("detail").build())
                .build();
    }

    static SdkClientException clientError(Throwable cause) {
        return SdkClientException.builder().message("client failure").cause(cause).build();
    }

    /** Tiempo agotado, E/S cortada con la petición en curso, 408 y 5xx: el resultado es desconocido. */
    static Stream<Arguments> ambiguous() {
        return Stream.of(
                Arguments.of("ApiCallAttemptTimeout", ApiCallAttemptTimeoutException.builder().message("t").build()),
                Arguments.of("ApiCallTimeout", ApiCallTimeoutException.builder().message("t").build()),
                Arguments.of("SocketTimeout", clientError(new SocketTimeoutException("Read timed out"))),
                Arguments.of("connection reset", clientError(new IOException("Connection reset"))),
                Arguments.of("unchecked IO", clientError(new UncheckedIOException(new IOException("Unexpected end of file")))),
                Arguments.of("408 RequestTimeout", s3Error(408, "RequestTimeout")),
                Arguments.of("500 InternalError", s3Error(500, "InternalError")),
                Arguments.of("502", s3Error(502, "BadGateway")),
                Arguments.of("503 SlowDown", s3Error(503, "SlowDown")),
                Arguments.of("504", s3Error(504, "GatewayTimeout")));
    }

    /** Credenciales, bucket, petición o configuración: repetir no cambia nada. */
    static Stream<Arguments> deterministic() {
        return Stream.of(
                Arguments.of("403 InvalidAccessKeyId", s3Error(403, "InvalidAccessKeyId")),
                Arguments.of("403 SignatureDoesNotMatch", s3Error(403, "SignatureDoesNotMatch")),
                Arguments.of("403 AccessDenied", s3Error(403, "AccessDenied")),
                Arguments.of("403 RequestTimeTooSkewed", s3Error(403, "RequestTimeTooSkewed")),
                Arguments.of("404 NoSuchBucket", NoSuchBucketException.builder().statusCode(404)
                        .awsErrorDetails(AwsErrorDetails.builder().errorCode("NoSuchBucket").build()).build()),
                Arguments.of("400 InvalidRequest", s3Error(400, "InvalidRequest")),
                Arguments.of("409", s3Error(409, "OperationAborted")),
                Arguments.of("411 MissingContentLength", s3Error(411, "MissingContentLength")),
                Arguments.of("413 EntityTooLarge", s3Error(413, "EntityTooLarge")),
                Arguments.of("UnknownHost", clientError(new UnknownHostException("media.invalid"))),
                Arguments.of("Connection refused", clientError(new ConnectException("Connection refused"))),
                Arguments.of("SSL handshake", clientError(new SSLHandshakeException("bad certificate"))),
                Arguments.of("sin causa de E/S", SdkClientException.builder().message("Unable to load credentials").build()));
    }

    private static byte[] bodyBytes(RequestBody body) throws IOException {
        try (InputStream in = body.contentStreamProvider().newStream()) {
            return in.readAllBytes();
        }
    }

    @Test
    void put_sendsTheObjectWithItsContentType_andNoAcl() throws IOException {
        adapter.put(KEY, BYTES, "image/jpeg");

        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> body = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3).putObject(request.capture(), body.capture());
        assertThat(request.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(request.getValue().key()).isEqualTo(KEY);
        assertThat(request.getValue().contentType()).isEqualTo("image/jpeg");
        // ADR-051 F1: bucket privado, ningún objeto con ACL pública
        assertThat(request.getValue().acl()).isNull();
        assertThat(request.getValue().aclAsString()).isNull();
        assertThat(bodyBytes(body.getValue())).isEqualTo(BYTES);
    }

    // ADR-051 F2: un reintento, misma clave y mismos bytes
    @Test
    void put_timeoutThenSuccess_retriesOnce_withTheSameKeyAndBytes() throws IOException {
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(ApiCallAttemptTimeoutException.builder().message("t").build())
                .thenReturn(PutObjectResponse.builder().build());

        adapter.put(KEY, BYTES, "image/jpeg");

        ArgumentCaptor<PutObjectRequest> requests = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> bodies = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3, times(2)).putObject(requests.capture(), bodies.capture());
        List<PutObjectRequest> sent = requests.getAllValues();
        assertThat(sent).extracting(PutObjectRequest::key).containsExactly(KEY, KEY);
        assertThat(sent).extracting(PutObjectRequest::bucket).containsExactly(BUCKET, BUCKET);
        assertThat(sent).extracting(PutObjectRequest::contentType).containsExactly("image/jpeg", "image/jpeg");
        assertThat(bodyBytes(bodies.getAllValues().get(0))).isEqualTo(BYTES);
        assertThat(bodyBytes(bodies.getAllValues().get(1))).isEqualTo(BYTES);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ambiguous")
    void put_twoAmbiguousFailures_throwsUnavailable_afterExactlyTwoAttempts(String description, RuntimeException failure) {
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenThrow(failure, failure);

        assertThatThrownBy(() -> adapter.put(KEY, BYTES, "image/jpeg"))
                .isInstanceOf(MediaStorageUnavailableException.class)
                .hasNoCause();

        verify(s3, times(2)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("deterministic")
    void put_deterministicFailure_isNotRetried(String description, RuntimeException failure) {
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenThrow(failure);

        assertThatThrownBy(() -> adapter.put(KEY, BYTES, "image/jpeg"))
                .isInstanceOf(MediaStorageUnavailableException.class)
                .hasNoCause();

        verify(s3, times(1)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    // ADR-051 F1: el log lleva clave, intento, clase, estado y código; nunca el mensaje del proveedor
    @Test
    void failedAttempts_areLogged_withKeyAttemptClassStatusAndCode_butNeverTheProviderMessage(CapturedOutput output) {
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(s3Error(503, "SlowDown"), s3Error(403, "AccessDenied"));

        assertThatThrownBy(() -> adapter.put(KEY, BYTES, "image/jpeg"))
                .isInstanceOf(MediaStorageUnavailableException.class);

        assertThat(output.getAll())
                .contains("media.storage.put.failed objectKey=" + KEY + " attempt=1 retry=true cause=S3Exception status=503 errorCode=SlowDown")
                .contains("media.storage.put.failed objectKey=" + KEY + " attempt=2 retry=false cause=S3Exception status=403 errorCode=AccessDenied")
                .doesNotContain("provider says").doesNotContain("detail");
    }

    @Test
    void get_returnsTheStoredBytes() {
        when(s3.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenReturn(ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), BYTES));

        assertThat(adapter.get(KEY)).hasValueSatisfying(bytes -> assertThat(bytes).isEqualTo(BYTES));

        ArgumentCaptor<GetObjectRequest> request = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3).getObjectAsBytes(request.capture());
        assertThat(request.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(request.getValue().key()).isEqualTo(KEY);
    }

    @Test
    void get_noSuchKey_isEmpty() {
        when(s3.getObjectAsBytes(any(GetObjectRequest.class))).thenThrow(NoSuchKeyException.builder().statusCode(404).build());

        assertThat(adapter.get(KEY)).isEmpty();
    }

    // ADR-051 F2 solo reintenta "al guardar"
    @Test
    void get_andDelete_failures_areNotRetried() {
        when(s3.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenThrow(ApiCallAttemptTimeoutException.builder().message("t").build());
        when(s3.deleteObject(any(DeleteObjectRequest.class))).thenThrow(s3Error(503, "SlowDown"));

        assertThatThrownBy(() -> adapter.get(KEY)).isInstanceOf(MediaStorageUnavailableException.class).hasNoCause();
        assertThatThrownBy(() -> adapter.delete(KEY)).isInstanceOf(MediaStorageUnavailableException.class).hasNoCause();

        verify(s3, times(1)).getObjectAsBytes(any(GetObjectRequest.class));
        verify(s3, times(1)).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void delete_sendsDeleteObject() {
        adapter.delete(KEY);

        ArgumentCaptor<DeleteObjectRequest> request = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3).deleteObject(request.capture());
        assertThat(request.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(request.getValue().key()).isEqualTo(KEY);
    }

    // P-5: las claves solo las genera el servicio (ULID); otra cosa es un error de programación
    @ParameterizedTest
    @ValueSource(strings = {"../etc/passwd", "a/b", "", "01jb2z3y4x5w6v7t8s9r0qpnmk", "01JB2Z3Y4X5W6V7T8S9R0QPNM"})
    void aKeyThatIsNotAUlid_isRejected_withoutCallingTheProvider(String key) {
        assertThatThrownBy(() -> adapter.put(key, BYTES, "image/jpeg")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.get(key)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.delete(key)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(s3);
    }

    @Test
    void nothingIsSent_whenTheFirstAttemptSucceeds() {
        adapter.put(KEY, BYTES, "image/png");

        verify(s3, times(1)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        verify(s3, never()).deleteObject(any(DeleteObjectRequest.class));
    }
}
