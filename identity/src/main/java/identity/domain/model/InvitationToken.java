package identity.domain.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Token de invitación (ADR-049 D2, DD-62): 32 bytes de {@link SecureRandom} (256 bits) en Base64 URL sin relleno.
 * Solo se guarda su SHA-256; el token vive en memoria hasta que se envía por correo. {@link #toString()} nunca lo
 * muestra.
 */
public final class InvitationToken {

    private static final SecureRandom RANDOM = new SecureRandom();
    public static final int BYTES = 32;

    private final String value;

    private InvitationToken(String value) {
        this.value = value;
    }

    public static InvitationToken generate() {
        byte[] raw = new byte[BYTES];
        RANDOM.nextBytes(raw);
        return new InvitationToken(Base64.getUrlEncoder().withoutPadding().encodeToString(raw));
    }

    /** El token en claro: solo para componer el enlace del correo. */
    public String value() {
        return value;
    }

    public String hash() {
        return hashOf(value);
    }

    /** SHA-256 en hexadecimal: sin sal, porque el token tiene 256 bits y no se puede enumerar (DD-62). */
    public static String hashOf(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }

    @Override
    public String toString() {
        return "InvitationToken[***]";
    }
}
