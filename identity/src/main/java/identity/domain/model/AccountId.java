package identity.domain.model;

import com.github.f4b6a3.ulid.UlidCreator;

public record AccountId(String value) {
    public AccountId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("AccountId cannot be null or blank");
        }
    }

    public static AccountId generate() {
        return new AccountId(UlidCreator.getUlid().toString());
    }
}
