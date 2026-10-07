package com.traceability.app.application.payments;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Firma HMAC-SHA256 del webhook simulado (`propuesta-d-api.md` A2.2). Solo existe con el proveedor simulado activo.
 * El secreto llega por {@code TRACEABILITY_DEMO_WEBHOOK_SECRET}, sin valor por defecto; la aplicación no arranca si
 * falta o tiene menos de 32 bytes (mismo criterio que el secreto JWT de ADR-047). Nunca se registra.
 */
@Component
@ConditionalOnProperty(name = "traceability.demo.simulated-payments", havingValue = "true")
public class SimulatedWebhookSignature {

    static final int MIN_SECRET_BYTES = 32;

    private final byte[] secret;

    public SimulatedWebhookSignature(@Value("${traceability.demo.webhook-secret:}") String secret) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("traceability.demo.webhook-secret must be set (>= 32 bytes) when "
                    + "traceability.demo.simulated-payments=true");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    /** Firma en hexadecimal del cuerpo en bruto. */
    public String sign(String rawBody) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 not available", e);
        }
    }

    public boolean verify(String rawBody, String signature) {
        if (rawBody == null || signature == null) {
            return false;
        }
        return MessageDigest.isEqual(sign(rawBody).getBytes(StandardCharsets.US_ASCII),
                signature.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
    }
}
