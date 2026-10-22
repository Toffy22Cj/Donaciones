package com.traceability.api.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan de la Tarea 2 de ADR-051, P-T2-6: textos fijos para 413, 415 y 503, sin datos de la petición ni el mensaje de la
 * excepción (antes usaban el texto por defecto).
 */
class ApiExceptionHandlerMediaDetailsTest {

    static final String MARKER = "SECRET-MARKER-media";

    static class TooLarge extends RuntimeException { TooLarge() { super(MARKER); } }
    static class Unsupported extends RuntimeException { Unsupported() { super(MARKER); } }
    static class Unavailable extends RuntimeException { Unavailable() { super(MARKER); } }

    private final ApiExceptionHandler handler = new ApiExceptionHandler(List.of(new ApiErrorMappings() {
        @Override
        public String module() {
            return "test";
        }

        @Override
        public List<ApiErrorMapping> mappings() {
            return List.of(new ApiErrorMapping(TooLarge.class, HttpStatus.PAYLOAD_TOO_LARGE, "ImageTooLarge"),
                    new ApiErrorMapping(Unsupported.class, HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UnsupportedImageFormat"),
                    new ApiErrorMapping(Unavailable.class, HttpStatus.SERVICE_UNAVAILABLE, "MediaStorageUnavailable"));
        }
    }));

    @Test
    void the413_415And503_haveTheirOwnFixedDetail() {
        assertThat(detail(handler.handle(new TooLarge()))).isEqualTo("The image is too large.");
        assertThat(detail(handler.handle(new Unsupported()))).isEqualTo("The image format is not supported.");
        assertThat(detail(handler.handle(new Unavailable())))
                .isEqualTo("The service is temporarily unavailable. Try again later.");
    }

    private static String detail(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getBody().toString()).doesNotContain(MARKER);
        return (String) response.getBody().get("detail");
    }
}
