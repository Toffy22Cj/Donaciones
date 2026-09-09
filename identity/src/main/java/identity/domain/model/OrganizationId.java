package identity.domain.model;

import com.github.f4b6a3.ulid.UlidCreator;

public record OrganizationId(String value) {
    public OrganizationId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("OrganizationId cannot be null or blank");
        }
    }

    public static OrganizationId generate() {
        return new OrganizationId(UlidCreator.getUlid().toString());
    }
}
