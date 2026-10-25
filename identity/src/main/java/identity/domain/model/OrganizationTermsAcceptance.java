package identity.domain.model;

import java.time.Instant;
import java.util.Objects;

/** Aceptación de los términos de organización al crearla (ADR-050 D2): la versión, la cuenta que aceptó y cuándo. */
public record OrganizationTermsAcceptance(String version, AccountId acceptedBy, Instant acceptedAt) {
    public OrganizationTermsAcceptance {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(acceptedBy, "acceptedBy");
        Objects.requireNonNull(acceptedAt, "acceptedAt");
    }
}
