package identity.domain.model;

import identity.domain.exception.InvalidEmailFormatException;

import java.util.regex.Pattern;

public record Email(String value) {
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,6}$");

    public Email {
        if (value == null || value.isBlank()) {
            throw new InvalidEmailFormatException("Email cannot be null or blank");
        }
        if (!EMAIL_PATTERN.matcher(value).matches()) {
            throw new InvalidEmailFormatException("Invalid email format: " + value);
        }
    }
}
