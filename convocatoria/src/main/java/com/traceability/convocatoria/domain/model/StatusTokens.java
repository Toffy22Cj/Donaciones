package com.traceability.convocatoria.domain.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Credencial de consulta de una intención (Enmienda 3 de ADR-037, D6): 256 bits aleatorios en base64url; se guarda
 * solo su SHA-256 y caduca a las 24 h. La comparación es en tiempo constante.
 */
public final class StatusTokens {

    public static final Duration TTL = Duration.ofHours(24);
    private static final SecureRandom RANDOM = new SecureRandom();

    private StatusTokens() {}

    public static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** {@code true} si {@code token} corresponde a {@code expectedHash}; nunca lanza con un token mal formado. */
    public static boolean matches(String token, String expectedHash) {
        if (token == null || expectedHash == null) {
            return false;
        }
        return MessageDigest.isEqual(hash(token).getBytes(StandardCharsets.US_ASCII),
                expectedHash.getBytes(StandardCharsets.US_ASCII));
    }
}
