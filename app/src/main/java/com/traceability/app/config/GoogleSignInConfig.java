package com.traceability.app.config;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.traceability.app.infrastructure.google.NimbusGoogleIdTokenVerifier;
import identity.application.port.out.GoogleIdTokenVerifierPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.MalformedURLException;
import java.net.URI;
import java.util.Arrays;
import java.util.List;

/**
 * [Plan] Google (ADR-050 D9; P-G-8 y P-G-9). Los ID de cliente salen de {@code GOOGLE_OAUTH_CLIENT_IDS}, separados por
 * comas y <b>nunca</b> con valores reales en el repositorio (G-1); el secreto del cliente no se usa. Sin ellos, la
 * aplicación arranca y el login con Google da el 401 uniforme. El JWKS se pide la primera vez que hace falta, con
 * conexión y lectura de 2 s, un reintento, caché de 5 min y 15 s de refresco.
 */
@Configuration
public class GoogleSignInConfig {

    private static final Logger log = LoggerFactory.getLogger(GoogleSignInConfig.class);
    static final int TIMEOUT_MILLIS = 2_000;
    static final long CACHE_TTL_MILLIS = 5 * 60_000L;
    static final long CACHE_REFRESH_MILLIS = 15_000L;

    @Bean
    public GoogleIdTokenVerifierPort googleIdTokenVerifier(
            @Value("${traceability.google.client-ids:}") String clientIds,
            @Value("${traceability.google.jwks-uri:https://www.googleapis.com/oauth2/v3/certs}") String jwksUri)
            throws MalformedURLException {
        List<String> ids = Arrays.stream(clientIds.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
        if (ids.isEmpty()) {
            log.info("google-sign-in disabled: no client ids configured");
        }
        JWKSource<SecurityContext> keys = JWKSourceBuilder
                .<SecurityContext>create(URI.create(jwksUri).toURL(),
                        new DefaultResourceRetriever(TIMEOUT_MILLIS, TIMEOUT_MILLIS, JWKSourceBuilder.DEFAULT_HTTP_SIZE_LIMIT))
                .cache(CACHE_TTL_MILLIS, CACHE_REFRESH_MILLIS)
                .retrying(true)
                .build();
        return new NimbusGoogleIdTokenVerifier(keys, ids);
    }
}
