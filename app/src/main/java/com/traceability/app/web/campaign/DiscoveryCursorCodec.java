package com.traceability.app.web.campaign;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

/**
 * Cursor opaco del descubrimiento (T-35; DD-53 rehecha por indicación de Carlos, 2026-10-07): el último
 * {@code publicCode} de la página, cifrado y autenticado con AES-256-GCM e IV aleatorio, en Base64 URL. El cliente no
 * puede leerlo ni fabricarlo; uno alterado, ajeno o de otra clave es inválido (→ 400). Solo JDK, sin dependencias.
 * <p>
 * Clave: {@code TRACEABILITY_DISCOVERY_CURSOR_KEY} (32 bytes en Base64) si existe; si no, una aleatoria por proceso
 * (los cursores dejan de valer al reiniciar y el cliente vuelve a la primera página). DD-58.
 */
public final class DiscoveryCursorCodec {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKey key;

    private DiscoveryCursorCodec(byte[] key) {
        this.key = new SecretKeySpec(key, "AES");
    }

    public static DiscoveryCursorCodec withRandomKey() {
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        return new DiscoveryCursorCodec(key);
    }

    public static DiscoveryCursorCodec fromConfiguredKey(String base64Key) {
        byte[] key;
        try {
            key = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            key = new byte[0];
        }
        if (key.length != 32) {
            throw new IllegalStateException("TRACEABILITY_DISCOVERY_CURSOR_KEY debe ser una clave de 32 bytes en Base64");
        }
        return new DiscoveryCursorCodec(key);
    }

    public String encode(String publicCode) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(publicCode.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo cifrar el cursor", e);
        }
    }

    /** Vacío si el cursor no es uno emitido con esta clave. */
    public Optional<String> decode(String cursor) {
        try {
            byte[] raw = Base64.getUrlDecoder().decode(cursor);
            if (raw.length <= IV_BYTES + TAG_BITS / 8) {
                return Optional.empty();
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES));
            // GCM autentica: solo un cursor emitido con esta clave descifra; el formato lo comprueba quien llama
            return Optional.of(new String(cipher.doFinal(raw, IV_BYTES, raw.length - IV_BYTES), StandardCharsets.US_ASCII));
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            return Optional.empty();
        }
    }
}
