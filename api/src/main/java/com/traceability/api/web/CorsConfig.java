package com.traceability.api.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * CORS (S-03, Carlos 2026-10-07). Los orígenes permitidos llegan solo por la variable de entorno
 * {@code TRACEABILITY_CORS_ALLOWED_ORIGINS} (lista separada por comas: desarrollo y demo). Nunca {@code *} ni comodines:
 * un valor así impide arrancar. Sin la variable, ningún origen cruzado está permitido.
 * <ul>
 *   <li>sin credenciales ({@code Access-Control-Allow-Credentials} nunca se envía): la API usa {@code Authorization},
 *   no cookies;</li>
 *   <li>cabeceras de petición: {@code Authorization}, {@code Command-Id}, {@code Intent-Token} y {@code Content-Type}
 *   (esta última es necesaria para enviar JSON desde el navegador);</li>
 *   <li>cabecera expuesta: {@code Location} (la división responde {@code 202} con ella).</li>
 * </ul>
 * El filtro va antes que los de autenticación: un preflight ({@code OPTIONS}) nunca llega a pedir JWT.
 */
@Configuration
public class CorsConfig {

    public static final List<String> ALLOWED_HEADERS = List.of("Authorization", "Command-Id", "Intent-Token", "Content-Type");
    public static final List<String> EXPOSED_HEADERS = List.of("Location");
    public static final List<String> ALLOWED_METHODS = List.of("GET", "POST", "OPTIONS");

    /** {@code esquema://host[:puerto]}, sin ruta, barra final ni comodines. */
    private static final Pattern ORIGIN = Pattern.compile("^https?://[A-Za-z0-9.-]+(:[0-9]{1,5})?$");

    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilter(
            @Value("${traceability.web.cors.allowed-origins:}") String allowedOrigins) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(parseOrigins(allowedOrigins));
        config.setAllowedMethods(ALLOWED_METHODS);
        config.setAllowedHeaders(ALLOWED_HEADERS);
        config.setExposedHeaders(EXPOSED_HEADERS);
        config.setAllowCredentials(false);
        config.setMaxAge(600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/v1/**", config);
        FilterRegistrationBean<CorsFilter> registration = new FilterRegistrationBean<>(new CorsFilter(source));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    /** Lista de orígenes exactos; vacía si no hay ninguno. Un comodín o un origen mal formado impide arrancar. */
    public static List<String> parseOrigins(String value) {
        List<String> origins = new ArrayList<>();
        if (value == null || value.isBlank()) {
            return origins;
        }
        for (String raw : value.split(",")) {
            String origin = raw.trim();
            if (origin.isEmpty()) {
                continue;
            }
            if (origin.contains("*") || !ORIGIN.matcher(origin).matches()) {
                throw new IllegalStateException(
                        "TRACEABILITY_CORS_ALLOWED_ORIGINS admite solo orígenes exactos (esquema://host[:puerto]); nunca '*'");
            }
            origins.add(origin);
        }
        return List.copyOf(origins);
    }
}
