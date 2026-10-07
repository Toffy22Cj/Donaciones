package identity.domain.model;

import identity.domain.exception.PasswordTooShortException;

/**
 * Contraseña en claro antes de cifrarla (H-P2-1, Carlos, 2026-10-07): al menos {@value #MIN_LENGTH} caracteres
 * (puntos de código Unicode). No se recorta ni se normaliza: se cifra tal cual. Nunca se imprime.
 */
public record PlainPassword(String value) {

    public static final int MIN_LENGTH = 12;

    public PlainPassword {
        if (value == null || value.isBlank() || value.codePointCount(0, value.length()) < MIN_LENGTH) {
            throw new PasswordTooShortException("Password must have at least " + MIN_LENGTH + " characters");
        }
    }

    @Override
    public String toString() {
        return "PlainPassword[***]";
    }
}
