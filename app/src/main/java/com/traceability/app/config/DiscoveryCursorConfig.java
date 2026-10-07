package com.traceability.app.config;

import com.traceability.app.web.campaign.DiscoveryCursorCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Clave del cursor opaco del descubrimiento (DD-53, Carlos, 2026-10-07): solo por la variable de entorno
 * {@code TRACEABILITY_DISCOVERY_CURSOR_KEY}, <b>sin valor por defecto</b>. Si falta, si no son 32 bytes en Base64 o si
 * coincide con otro secreto de la aplicación, no arranca (fail-fast). La clave nunca se registra ni aparece en un
 * mensaje de error.
 */
@Configuration
public class DiscoveryCursorConfig {

    @Bean
    public DiscoveryCursorCodec discoveryCursorCodec(
            @Value("${traceability.discovery.cursor-key}") String cursorKey,
            @Value("${traceability.security.jwt.signing-secret:}") String jwtSecret,
            @Value("${traceability.security.jwt.previous-signing-secret:}") String previousJwtSecret,
            @Value("${traceability.security.tracking-code-secret:}") String trackingCodeSecret,
            @Value("${traceability.security.asset-ref-secret:}") String assetRefSecret,
            @Value("${traceability.demo.webhook-secret:}") String webhookSecret) {
        return DiscoveryCursorCodec.fromConfiguredKey(cursorKey,
                List.of(jwtSecret, previousJwtSecret, trackingCodeSecret, assetRefSecret, webhookSecret));
    }
}
