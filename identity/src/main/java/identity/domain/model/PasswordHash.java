package identity.domain.model;

public record PasswordHash(String value) {
    public PasswordHash {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("PasswordHash cannot be null or blank");
        }
    }
}
