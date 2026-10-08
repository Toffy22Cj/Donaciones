package identity.domain.model;

import identity.domain.exception.InvalidEmailFormatException;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Dirección de correo de una cuenta. Se normaliza a minúsculas ({@link Locale#ROOT}) al construirla, así que el
 * registro, el login, la unicidad de cuentas y las invitaciones tratan {@code Ana@Example.org} y
 * {@code ana@example.org} como la misma dirección (encargo 5, punto 4; cierra el hallazgo de ADR-049).
 */
public record Email(String value) {
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,6}$");

    public Email {
        if (value == null || value.isBlank()) {
            throw new InvalidEmailFormatException("Email cannot be null or blank");
        }
        if (!EMAIL_PATTERN.matcher(value).matches()) {
            throw new InvalidEmailFormatException("Invalid email format: " + value);
        }
        value = value.toLowerCase(Locale.ROOT);
    }
}
