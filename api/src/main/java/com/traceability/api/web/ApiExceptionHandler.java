package com.traceability.api.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * El único manejador de errores de la API (plan B6-0 §2.3; Q-B60-4). Cada módulo aporta sus traducciones como un bean
 * {@link ApiErrorMappings}; si dos declaran la misma excepción, el constructor falla y la aplicación no arranca.
 *
 * <p>Para cada excepción se usa la traducción de su clase o, si no hay, la de la superclase más cercana. Las de Spring
 * que implementan {@link ErrorResponse} (405, 415, parámetro ausente…) conservan su código. El resto es 500.
 *
 * <p><strong>Ningún cuerpo lleva el mensaje de la excepción</strong>, ids internos ni datos de la petición: los textos
 * son fijos por código. Por eso el cuerpo es un mapa con la forma de {@code ProblemDetail} (RFC 9457) y no un
 * {@code ProblemDetail}: Spring rellenaría su {@code instance} con la ruta de la petición, que puede llevar ids.
 * El log de un 500 guarda la clase, las clases de las causas y el {@code correlationId}, nunca los mensajes ni la
 * traza: un mensaje de dominio puede incluir datos personales o credenciales.
 *
 * <p>Los controladores de Fase 3 y el login componen su propio {@code ProblemDetail} y no pasan por aquí.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class ApiExceptionHandler {

    public static final String CORRELATION_ID = "correlationId";

    public static final String BAD_REQUEST = "BadRequest";
    public static final String FORBIDDEN = "Forbidden";
    public static final String NOT_FOUND = "NotFound";
    public static final String INTERNAL_ERROR = "InternalError";

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private static final Map<Integer, String> DETAILS = Map.of(
            400, "The request is not valid.",
            401, "Authentication is required.",
            403, "You are not allowed to perform this operation.",
            404, "The resource was not found.",
            409, "The operation conflicts with the current state of the resource.",
            500, "An unexpected error occurred. Quote the correlationId when contacting support.");
    private static final String DEFAULT_DETAIL = "The request could not be processed.";

    private final Map<Class<? extends Throwable>, ApiErrorMapping> mappings;

    public ApiExceptionHandler(List<ApiErrorMappings> modules) {
        Map<Class<? extends Throwable>, ApiErrorMapping> byException = new HashMap<>();
        Map<Class<? extends Throwable>, String> owner = new HashMap<>();
        List<String> duplicates = new ArrayList<>();
        for (ApiErrorMappings module : modules) {
            for (ApiErrorMapping mapping : module.mappings()) {
                String previous = owner.putIfAbsent(mapping.exception(), module.module());
                if (previous != null) {
                    duplicates.add(mapping.exception().getName() + " (" + previous + ", " + module.module() + ")");
                } else {
                    byException.put(mapping.exception(), mapping);
                }
            }
        }
        if (!duplicates.isEmpty()) {
            throw new IllegalStateException("Exceptions mapped more than once: " + String.join("; ", duplicates));
        }
        this.mappings = Map.copyOf(byException);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handle(Exception ex) {
        ApiErrorMapping mapping = mappingFor(ex.getClass());
        if (mapping != null) {
            String title = mapping.title() != null ? mapping.title() : ruleName(ex.getClass());
            return problem(mapping.status(), title, null, HttpHeaders.EMPTY);
        }
        if (ex instanceof ErrorResponse springError) {
            HttpStatusCode status = springError.getStatusCode();
            HttpStatus known = HttpStatus.resolve(status.value());
            String title = known == null ? "Error" : known.getReasonPhrase().replace(" ", "").replace("-", "");
            return problem(status, title, null, springError.getHeaders());
        }
        String correlationId = UUID.randomUUID().toString();
        log.error("Unhandled exception class={} causes={} correlationId={} at={}",
                ex.getClass().getName(), causeClasses(ex), correlationId, firstFrame(ex));
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_ERROR, correlationId, HttpHeaders.EMPTY);
    }

    private ApiErrorMapping mappingFor(Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            ApiErrorMapping mapping = mappings.get(c);
            if (mapping != null) return mapping;
        }
        return null;
    }

    /** {@code InvalidAssetTransitionException} → {@code InvalidAssetTransition}. */
    private static String ruleName(Class<?> type) {
        String name = type.getSimpleName();
        return name.endsWith("Exception") ? name.substring(0, name.length() - "Exception".length()) : name;
    }

    private static ResponseEntity<Map<String, Object>> problem(HttpStatusCode status, String title, String correlationId,
                                                               HttpHeaders headers) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "about:blank");
        body.put("title", title);
        body.put("status", status.value());
        body.put("detail", DETAILS.getOrDefault(status.value(), DEFAULT_DETAIL));
        if (correlationId != null) body.put(CORRELATION_ID, correlationId);
        return ResponseEntity.status(status).headers(headers).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }

    private static List<String> causeClasses(Throwable ex) {
        List<String> causes = new ArrayList<>();
        for (Throwable c = ex.getCause(); c != null && c != ex && causes.size() < 10; c = c.getCause()) {
            causes.add(c.getClass().getName());
        }
        return causes;
    }

    private static String firstFrame(Throwable ex) {
        StackTraceElement[] trace = ex.getStackTrace();
        return trace.length == 0 ? "?" : trace[0].getClassName() + "." + trace[0].getMethodName() + ":" + trace[0].getLineNumber();
    }
}
