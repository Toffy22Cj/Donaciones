package identity.domain.model;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Token de un dispositivo para entrar con biometría: 32 bytes de {@link SecureRandom} (256 bits) en Base64 URL sin
 * relleno, como el de invitación (DD-62). El backend solo guarda su SHA-256; el token en claro se entrega una vez, al
 * dar de alta el dispositivo, y lo guarda el teléfono cifrado. {@link #toString()} nunca lo muestra.
 */
public final class DeviceToken {

    private static final SecureRandom RANDOM = new SecureRandom();
    public static final int BYTES = 32;

    private final String value;

    private DeviceToken(String value) {
        this.value = value;
    }

    public static DeviceToken generate() {
        byte[] raw = new byte[BYTES];
        RANDOM.nextBytes(raw);
        return new DeviceToken(Base64.getUrlEncoder().withoutPadding().encodeToString(raw));
    }

    /** El token en claro: solo para la respuesta del alta. */
    public String value() {
        return value;
    }

    public String hash() {
        return hashOf(value);
    }

    /** SHA-256 en hexadecimal, sin sal: el token tiene 256 bits y no se puede enumerar (como DD-62). */
    public static String hashOf(String token) {
        return InvitationToken.hashOf(token);
    }

    @Override
    public String toString() {
        return "DeviceToken[***]";
    }
}
